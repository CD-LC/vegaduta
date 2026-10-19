package com.agenticai.core.agents.lab;

import com.agenticai.core.agents.lab.AgentLabDtos.LabRunDocument;
import com.agenticai.core.resilience.RateLimitedLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Redis-backed {@link AgentLabRunStore}. No table and no migration: a lab
 * run is an experiment whose results matter for minutes, so its whole state
 * is one JSON document with a TTL.
 *
 * <p>Redis rather than a map in this JVM for the reason {@code
 * ChatTurnCancelService} documents: the poll, the cancel and the worker can
 * each land on a different replica. Losing a run to a restart is accepted -
 * its liveness mark ({@link #ALIVE_TTL}) lapses and the next poll reports it
 * as stopped (see {@code AgentLabRunService#get}).
 *
 * <p>Times inside the document are epoch-millisecond longs, so the stored
 * form does not depend on how the injected {@link ObjectMapper} writes
 * {@code Instant}.
 */
@Component
public class RedisAgentLabRunStore implements AgentLabRunStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAgentLabRunStore.class);

    /** How long results stay readable. Restarted by every write, so it counts from the last progress. */
    static final Duration RUN_TTL = Duration.ofMinutes(AgentLabRunService.RUN_TTL_MINUTES);

    /** Outlives the 20-minute run deadline; a flag for a finished run just expires. */
    static final Duration CANCEL_TTL = Duration.ofMinutes(30);

    /**
     * Longer than the run deadline, so a healthy run never loses its lock
     * mid-flight, and short enough that a lock nobody released (worker
     * killed, release lost to a Redis blip) frees itself the same hour.
     */
    static final Duration LOCK_TTL = Duration.ofMinutes(25);

    /** Compare-and-delete: only the run that holds the lock may free it. */
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class);

    /**
     * Three missed refreshes. {@code AgentLabRunService} re-marks every run
     * it holds each {@code HEARTBEAT_SECONDS} (30), so a run whose JVM died
     * reads as dead within a minute and a half, while one slow refresh or a
     * short Redis blip does not fail a healthy run.
     */
    static final Duration ALIVE_TTL = Duration.ofSeconds(90);

    /**
     * Compare-and-set for {@link #replaceIfUnchanged}: the replacement is
     * written only while the stored document still has the status, {@code
     * updatedAtMs} and {@code completedCalls} the caller read. Compared as
     * decoded values, not as raw JSON, so it does not depend on this build
     * serialising a document byte-for-byte the way the writer did. Epoch
     * milliseconds are far inside the range a Lua number holds exactly.
     */
    private static final DefaultRedisScript<Long> REPLACE_IF_UNCHANGED = new DefaultRedisScript<>(
        "local raw = redis.call('get', KEYS[1]) "
            + "if not raw then return 0 end "
            + "local ok, doc = pcall(cjson.decode, raw) "
            + "if not ok or type(doc) ~= 'table' or type(doc.run) ~= 'table' then return 0 end "
            + "if doc.run.status ~= ARGV[1] or doc.run.updatedAtMs ~= tonumber(ARGV[2]) "
            + "or doc.run.completedCalls ~= tonumber(ARGV[3]) then return 0 end "
            + "redis.call('set', KEYS[1], ARGV[4], 'EX', ARGV[5]) "
            + "return 1",
        Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisAgentLabRunStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** One WARN per minute while Redis is down, not one per poll. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));

    /**
     * A lab run lives only in Redis, so with Redis down there is no local fallback to degrade to. What
     * changes is the reporting: a clean 503 (mapped by GlobalExceptionHandler) instead of an unmapped
     * Redis exception hitting the catch-all (500 + an Issues-portal record per poll). Bug fix, no flag.
     */
    private static <T> T redis(Supplier<T> op) {
        try {
            return op.get();
        } catch (org.springframework.dao.DataAccessException e) {
            REDIS_DOWN_LOG.warn("agent-lab", "Agent Lab store unavailable (Redis error: "
                + e.getClass().getSimpleName() + ")");
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Agent Lab is temporarily unavailable. Try again shortly.", e);
        }
    }

    private static void redisVoid(Runnable op) {
        redis(() -> {
            op.run();
            return null;
        });
    }

    static String runKey(String tenantId, String runId) {
        return "agentic:lab:run:" + tenantId + ":" + runId;
    }

    static String cancelKey(String tenantId, String runId) {
        return "agentic:lab:cancel:" + tenantId + ":" + runId;
    }

    static String lockKey(String tenantId, String subject) {
        return "agentic:lab:active:" + tenantId + ":" + subject;
    }

    static String aliveKey(String tenantId, String runId) {
        return "agentic:lab:alive:" + tenantId + ":" + runId;
    }

    @Override
    public void save(String tenantId, LabRunDocument doc) {
        String json = toJson(doc);
        redisVoid(() -> redisTemplate.opsForValue().set(runKey(tenantId, doc.run().runId()), json, RUN_TTL));
    }

    private String toJson(LabRunDocument doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise lab run " + doc.run().runId(), e);
        }
    }

    @Override
    public boolean replaceIfUnchanged(String tenantId, LabRunDocument expected, LabRunDocument replacement) {
        String replacementJson = toJson(replacement);
        Long written = redis(() -> redisTemplate.execute(
            REPLACE_IF_UNCHANGED,
            List.of(runKey(tenantId, expected.run().runId())),
            expected.run().status(),
            Long.toString(expected.run().updatedAtMs()),
            Integer.toString(expected.run().completedCalls()),
            replacementJson,
            Long.toString(RUN_TTL.toSeconds())));
        return written != null && written == 1L;
    }

    @Override
    public void markAlive(String tenantId, String runId) {
        redisVoid(() -> redisTemplate.opsForValue().set(aliveKey(tenantId, runId), "1", ALIVE_TTL));
    }

    @Override
    public boolean isAlive(String tenantId, String runId) {
        return Boolean.TRUE.equals(redis(() -> redisTemplate.hasKey(aliveKey(tenantId, runId))));
    }

    @Override
    public Optional<LabRunDocument> find(String tenantId, String runId) {
        String json = redis(() -> redisTemplate.opsForValue().get(runKey(tenantId, runId)));
        if (json == null) {
            return Optional.empty();
        }
        try {
            LabRunDocument doc = objectMapper.readValue(json, LabRunDocument.class);
            return doc == null || doc.run() == null ? Optional.empty() : Optional.of(doc);
        } catch (JsonProcessingException e) {
            // A document this build cannot read (written by another version)
            // is as gone as an expired one - and must not 500 every poll.
            log.warn("Unreadable lab run document for run {} - treating it as expired", runId, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String tenantId, String runId) {
        redisVoid(() -> redisTemplate.delete(runKey(tenantId, runId)));
    }

    @Override
    public boolean acquireLock(String tenantId, String subject, String runId) {
        return Boolean.TRUE.equals(redis(() ->
            redisTemplate.opsForValue().setIfAbsent(lockKey(tenantId, subject), runId, LOCK_TTL)));
    }

    @Override
    public void releaseLock(String tenantId, String subject, String runId) {
        redisVoid(() -> redisTemplate.execute(RELEASE_LOCK, List.of(lockKey(tenantId, subject)), runId));
    }

    @Override
    public Optional<String> lockHolder(String tenantId, String subject) {
        return Optional.ofNullable(redis(() -> redisTemplate.opsForValue().get(lockKey(tenantId, subject))));
    }

    @Override
    public void requestCancel(String tenantId, String runId) {
        redisVoid(() -> redisTemplate.opsForValue().set(cancelKey(tenantId, runId), "1", CANCEL_TTL));
    }

    @Override
    public boolean isCancelRequested(String tenantId, String runId) {
        return Boolean.TRUE.equals(redis(() -> redisTemplate.hasKey(cancelKey(tenantId, runId))));
    }
}
