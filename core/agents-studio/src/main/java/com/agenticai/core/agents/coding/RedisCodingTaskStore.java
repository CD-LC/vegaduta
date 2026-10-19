package com.agenticai.core.agents.coding;

import com.agenticai.core.agents.coding.CodingTaskDtos.TaskDocument;
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
 * Redis-backed {@link CodingTaskStore}. No table and no migration: a task is one JSON document with
 * a TTL (the same posture as the Agent Lab's run store). Redis rather than a map in this JVM so the
 * poll, the approve click and the worker can each land on a different replica; the live sandbox
 * itself is JVM-local, so a restart ends a task (its liveness mark lapses and the next poll reports
 * it as stopped).
 */
@Component
public class RedisCodingTaskStore implements CodingTaskStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCodingTaskStore.class);
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));

    static final Duration TASK_TTL = Duration.ofHours(6);
    static final Duration LOCK_TTL = Duration.ofMinutes(75);
    static final Duration ALIVE_TTL = Duration.ofSeconds(90);
    static final Duration DECISION_TTL = Duration.ofHours(2);
    static final int INDEX_MAX = 200;

    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisCodingTaskStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private static <T> T guarded(Supplier<T> op) {
        try {
            return op.get();
        } catch (org.springframework.dao.DataAccessException e) {
            REDIS_DOWN_LOG.warn("coding-task", "Coding task store unavailable (Redis error: "
                + e.getClass().getSimpleName() + ")");
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Coding tasks are temporarily unavailable. Try again shortly.", e);
        }
    }

    private static void guardedVoid(Runnable op) {
        guarded(() -> {
            op.run();
            return null;
        });
    }

    static String taskKey(String tenant, String id) {
        return "agentic:coding:task:" + tenant + ":" + id;
    }

    static String diffKey(String tenant, String id) {
        return "agentic:coding:diff:" + tenant + ":" + id;
    }

    static String cancelKey(String tenant, String id) {
        return "agentic:coding:cancel:" + tenant + ":" + id;
    }

    static String decisionKey(String tenant, String id) {
        return "agentic:coding:decision:" + tenant + ":" + id;
    }

    static String lockKey(String tenant, String subject) {
        return "agentic:coding:active:" + tenant + ":" + subject;
    }

    static String aliveKey(String tenant, String id) {
        return "agentic:coding:alive:" + tenant + ":" + id;
    }

    static String indexKey(String tenant, String subject) {
        return "agentic:coding:recent:" + tenant + ":" + subject;
    }

    @Override
    public void save(String tenantId, TaskDocument doc) {
        String json;
        try {
            json = mapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise coding task " + doc.task().taskId(), e);
        }
        guardedVoid(() -> redis.opsForValue().set(taskKey(tenantId, doc.task().taskId()), json, TASK_TTL));
    }

    @Override
    public Optional<TaskDocument> find(String tenantId, String taskId) {
        String json = guarded(() -> redis.opsForValue().get(taskKey(tenantId, taskId)));
        if (json == null) {
            return Optional.empty();
        }
        try {
            TaskDocument doc = mapper.readValue(json, TaskDocument.class);
            return doc == null || doc.task() == null ? Optional.empty() : Optional.of(doc);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable coding task document for {} - treating it as expired", taskId, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String tenantId, String taskId) {
        guardedVoid(() -> redis.delete(taskKey(tenantId, taskId)));
    }

    @Override
    public void saveDiff(String tenantId, String taskId, String diff) {
        guardedVoid(() -> redis.opsForValue().set(diffKey(tenantId, taskId), diff, TASK_TTL));
    }

    @Override
    public Optional<String> findDiff(String tenantId, String taskId) {
        return Optional.ofNullable(guarded(() -> redis.opsForValue().get(diffKey(tenantId, taskId))));
    }

    @Override
    public void deleteDiff(String tenantId, String taskId) {
        guardedVoid(() -> redis.delete(diffKey(tenantId, taskId)));
    }

    @Override
    public boolean acquireLock(String tenantId, String subject, String taskId) {
        return Boolean.TRUE.equals(guarded(() ->
            redis.opsForValue().setIfAbsent(lockKey(tenantId, subject), taskId, LOCK_TTL)));
    }

    @Override
    public void releaseLock(String tenantId, String subject, String taskId) {
        guardedVoid(() -> redis.execute(RELEASE_LOCK, List.of(lockKey(tenantId, subject)), taskId));
    }

    @Override
    public Optional<String> lockHolder(String tenantId, String subject) {
        return Optional.ofNullable(guarded(() -> redis.opsForValue().get(lockKey(tenantId, subject))));
    }

    @Override
    public void requestCancel(String tenantId, String taskId) {
        guardedVoid(() -> redis.opsForValue().set(cancelKey(tenantId, taskId), "1", TASK_TTL));
    }

    @Override
    public boolean isCancelRequested(String tenantId, String taskId) {
        return Boolean.TRUE.equals(guarded(() -> redis.hasKey(cancelKey(tenantId, taskId))));
    }

    @Override
    public void setDecision(String tenantId, String taskId, String decision) {
        guardedVoid(() -> redis.opsForValue().set(decisionKey(tenantId, taskId), decision, DECISION_TTL));
    }

    @Override
    public Optional<String> decision(String tenantId, String taskId) {
        return Optional.ofNullable(guarded(() -> redis.opsForValue().get(decisionKey(tenantId, taskId))));
    }

    @Override
    public void clearDecision(String tenantId, String taskId) {
        guardedVoid(() -> redis.delete(decisionKey(tenantId, taskId)));
    }

    @Override
    public void markAlive(String tenantId, String taskId) {
        guardedVoid(() -> redis.opsForValue().set(aliveKey(tenantId, taskId), "1", ALIVE_TTL));
    }

    @Override
    public boolean isAlive(String tenantId, String taskId) {
        return Boolean.TRUE.equals(guarded(() -> redis.hasKey(aliveKey(tenantId, taskId))));
    }

    @Override
    public void index(String tenantId, String subject, String taskId) {
        guardedVoid(() -> {
            String key = indexKey(tenantId, subject);
            redis.opsForList().remove(key, 0, taskId);
            redis.opsForList().leftPush(key, taskId);
            redis.opsForList().trim(key, 0, INDEX_MAX - 1);
            redis.expire(key, TASK_TTL);
        });
    }

    @Override
    public List<String> recent(String tenantId, String subject, int limit) {
        List<String> ids = guarded(() -> redis.opsForList().range(indexKey(tenantId, subject), 0, Math.max(0, limit - 1)));
        return ids == null ? List.of() : ids;
    }

    @Override
    public List<String> recent(String tenantId, String subject, int offset, int limit) {
        long from = Math.max(0, offset);
        List<String> ids = guarded(() -> redis.opsForList().range(indexKey(tenantId, subject), from, from + Math.max(1, limit) - 1));
        return ids == null ? List.of() : ids;
    }

    @Override
    public long recentCount(String tenantId, String subject) {
        Long size = guarded(() -> redis.opsForList().size(indexKey(tenantId, subject)));
        return size == null ? 0 : size;
    }
}
