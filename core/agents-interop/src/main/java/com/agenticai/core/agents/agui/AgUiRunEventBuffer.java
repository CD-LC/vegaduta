package com.agenticai.core.agents.agui;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

/**
 * Durable event buffer behind AG-UI stream rejoin ("rejoinable streams with buffered events" - the
 * gap this closes: {@code POST .../agui/run} used to emit its mapped events exactly once, to
 * whoever held the socket; a dropped connection lost the whole turn's stream with no way back).
 *
 * <h2>Two layers, on purpose</h2>
 *
 * <ul>
 *   <li><b>Redis list per run</b> ({@code agui:runbuf:{tenant}:{scopedRunId}}, see {@link
 *       #scopedRunId}) - the durable layer. One entry per mapped {@link AgUiEvent} JSON; the list
 *       POSITION is the sequence number (a {@code RPUSH}'s return value IS the new length, so the
 *       seq assignment is atomic server-side with no separate counter key). Tenant-first key per
 *       the platform's cache-key rule; {@link #BUFFER_TTL} bounds retention to a rejoin window, not
 *       a log - a client that reconnects within 15 minutes gets every event back.
 *   <li><b>Same-pod live sink</b> ({@link #liveRuns}) - the tail layer. A {@code replay().all()}
 *       sink per in-flight run lets a rejoining client that lands on the pod actually running the
 *       turn keep receiving events live after the buffered replay, with zero gap (replay-all covers
 *       anything emitted between the Redis read and the sink subscription - the classic
 *       buffer-then-attach race). A rejoin that lands on a DIFFERENT pod gets the buffered history
 *       and a clean stream end; the client may poll again - the buffer keeps filling regardless of
 *       which pod it reads from.
 * </ul>
 *
 * <p>A run is marked done via {@link #finish} when its stream terminates (normally, on error, or on
 * client cancel), so a rejoin of a completed run replays the buffer and ends instead of hanging
 * waiting for a tail that can never arrive.
 */
@Service
public class AgUiRunEventBuffer {

    private static final Logger log = LoggerFactory.getLogger(AgUiRunEventBuffer.class);

    /**
     * One WARN per minute per call site while a dependency is down, instead of one (with a stack
     * trace) per request.
     */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    /**
     * How long a run's events remain rejoinable after the last append. 15 minutes covers every
     * realistic drop-and-reconnect window (mobile radio sleep, proxy idle timeout, tab crash) while
     * keeping Redis memory bounded - this is a rejoin buffer, deliberately not a durable event log
     * (the run's durable record is the chat history itself).
     */
    static final Duration BUFFER_TTL = Duration.ofMinutes(15);

    /** Redis list of event JSON, seq = 1-based list position. */
    private static String bufferKey(String tenantId, String runId) {
        return "agui:runbuf:" + tenantId + ":" + runId;
    }

    /**
     * Set when the run's stream terminates - lets a rejoin end cleanly instead of waiting on a live
     * tail.
     */
    private static String doneKey(String tenantId, String runId) {
        return "agui:runbuf:" + tenantId + ":" + runId + ":done";
    }

    /**
     * The buffer id for one caller's run: the client-chosen {@code runId} namespaced by the agent
     * it ran on and the subject (principal) that started it. The buffer is keyed on this, never on
     * the bare {@code runId}, so another agent's run, another user's run, or a deliberately
     * colliding {@code runId} can neither be read nor written into. A rejoin by someone else
     * resolves to a different, empty buffer, which is the same answer as for an unknown run.
     *
     * @param subject the authenticated principal name, or empty when the call has none
     * @param runId already validated against {@link #RUN_ID_PATTERN} (no {@code :}), so the result
     *     cannot spell another run's {@code :done} marker key
     */
    public static String scopedRunId(UUID agentId, String subject, String runId) {
        return agentId + "." + subjectDigest(subject == null ? "" : subject) + "." + runId;
    }

    /**
     * What a client-chosen run id may look like; anything else is rejected before it reaches a key.
     */
    static final Pattern RUN_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private static String subjectDigest(String subject) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * One buffered event with its sequence number - the unit the live sink carries so rejoins can
     * dedupe by seq.
     */
    public record BufferedEvent(long seq, String json) {}

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, Sinks.Many<BufferedEvent>> liveRuns =
            new ConcurrentHashMap<>();

    public AgUiRunEventBuffer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Open the live tail for a run that is about to start. Call BEFORE the event flux is subscribed
     * so a rejoin arriving mid-run always finds the sink. Keyed by tenant+runId - a client-chosen
     * runId can never collide across tenants.
     */
    public void open(String tenantId, String runId) {
        liveRuns.put(liveKey(tenantId, runId), Sinks.many().replay().all());
    }

    /**
     * Append one event to the durable buffer and offer it to this pod's live tail. Redis failures
     * degrade to live-only (logged, never thrown) - a sick Redis must not take the RUNNING turn
     * down with it; the client holding the socket still gets every event.
     *
     * @return the assigned sequence number (1-based).
     */
    public long record(String tenantId, String runId, String json) {
        long seq = -1;
        try {
            Long pushed = redis.opsForList().rightPush(bufferKey(tenantId, runId), json);
            seq = pushed == null ? -1 : pushed;
            redis.expire(bufferKey(tenantId, runId), BUFFER_TTL);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "AG-UI run buffer append failed for run {} - live stream unaffected", runId, e);
        }
        Sinks.Many<BufferedEvent> sink = liveRuns.get(liveKey(tenantId, runId));
        if (sink != null) {
            // EmitResult ignored on purpose: replay-all can only fail on
            // termination, and a terminated sink means the run already ended -
            // the event is safely in Redis either way.
            sink.tryEmitNext(new BufferedEvent(seq, json));
        }
        return seq;
    }

    /**
     * Every buffered event with seq &gt; {@code after}, in order. Never throws - a Redis miss reads
     * as an empty buffer.
     */
    public List<BufferedEvent> readAfter(String tenantId, String runId, long after) {
        List<BufferedEvent> out = new ArrayList<>();
        List<String> entries;
        try {
            entries = redis.opsForList().range(bufferKey(tenantId, runId), 0, -1);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "AG-UI run buffer read failed for run {} - returning empty", runId, e);
            return out;
        }
        if (entries == null) {
            return out;
        }
        for (int i = 0; i < entries.size(); i++) {
            long seq = i + 1L; // seq IS the list position - see class javadoc.
            if (seq > after) {
                out.add(new BufferedEvent(seq, entries.get(i)));
            }
        }
        return out;
    }

    /**
     * The same-pod live tail for an in-flight run, or {@code null} when the run is not executing on
     * this pod (finished, or running elsewhere). Replay-all semantics mean the returned flux
     * re-delivers the run's whole history - callers filter by seq.
     */
    public Sinks.Many<BufferedEvent> liveTail(String tenantId, String runId) {
        return liveRuns.get(liveKey(tenantId, runId));
    }

    /**
     * True once {@link #finish} has marked the run terminated - rejoins of finished runs replay and
     * end.
     */
    public boolean isFinished(String tenantId, String runId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(doneKey(tenantId, runId)));
        } catch (RuntimeException e) {
            // Fail open toward "finished": the buffer itself is the contract -
            // a client that sees its RUN_FINISHED/RUN_ERROR replayed needs no
            // tail anyway.
            return true;
        }
    }

    /**
     * Mark the run terminated: sets the done marker (TTL'd alongside the buffer) and completes +
     * drops the live sink so the map can't leak one entry per run forever.
     */
    public void finish(String tenantId, String runId) {
        Sinks.Many<BufferedEvent> sink = liveRuns.remove(liveKey(tenantId, runId));
        if (sink != null) {
            sink.tryEmitComplete();
        }
        try {
            redis.opsForValue().set(doneKey(tenantId, runId), "1", BUFFER_TTL);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("AG-UI run done-marker write failed for run {}", runId, e);
        }
    }

    /**
     * Current tenant for key scoping, or {@code "public"} when the run carries none (AG-UI runs
     * through the same authenticated filter chain as /chat, so a missing context means a test or an
     * internal caller - "public" keeps the key valid rather than blowing up a null into Redis).
     */
    public static String currentTenantKey() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("public");
    }

    private static String liveKey(String tenantId, String runId) {
        return tenantId + ":" + runId;
    }
}
