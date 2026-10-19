package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.memory.RedisScriptRefusal;
import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Server-side cancel for an in-flight chat turn ({@code POST
 * /api/agents/{agentId}/chat/sessions/{sessionId}/cancel}).
 *
 * <p><b>Why this exists:</b> until now the only way to stop a chat turn was client disconnect -
 * Reactor's cancel signal travelled up the SSE subscription and {@code AgentChatService}'s {@code
 * doOnCancel} persisted the partial reply. An operator on another device, a second tab, or a
 * channel-side admin had no handle on the turn at all. This is that handle: a Redis flag keyed by
 * {@code (agentId, sessionId)}, checked for every running turn on a pod by one shared poll (see
 * {@link #pollOnce}), not one poll per turn.
 *
 * <p><b>Why Redis, not an in-memory registry:</b> a {@code Disposable} map would be instant but
 * works only when the cancel request lands on the same replica as the turn - silently no-ops
 * otherwise. The flag is polled by the stream itself (see {@link #watchForCancellation}), so it
 * works regardless of which replica each request hit - same cross-request signalling pattern {@code
 * WorkflowRunCancellationService} established for Stop Run, applied to a token stream instead of a
 * node loop.
 *
 * <p><b>Semantics:</b> observing the flag cancels the turn's upstream subscription - which is
 * exactly the signal a client disconnect sends, so the existing {@code doOnCancel} partial-persist
 * (and every other {@code doFinally} cleanup) runs identically for operator cancel and browser
 * close. The flag is consumed on observation (delete-on-read), so a cancel can never bleed into the
 * NEXT turn on the same session; a flag set while no turn is running expires alone after {@link
 * #FLAG_TTL}.
 *
 * <p><b>Pause/resume is deliberately absent for chat:</b> a provider token stream has no
 * checkpointable state - there is nothing to suspend and re-enter. The honest run-control verbs for
 * chat are cancel and "send another message". Workflow runs are where pause/resume exists (V261).
 */
@Component
public class ChatTurnCancelService {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnCancelService.class);

    /**
     * One WARN per minute per call site while a dependency is down, instead of one (with a stack
     * trace) per request.
     */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    private static final String KEY_PREFIX = "agentic:chat-turn-cancel:";

    /**
     * Short on purpose: a cancel issued with no turn in flight must not linger long enough to kill
     * the NEXT turn on the same session. Two minutes bounds both the stream's worst-case detection
     * delay and that stale-flag window.
     */
    private static final Duration FLAG_TTL = Duration.ofMinutes(2);

    /**
     * How often THIS POD asks Redis which of its in-flight turns were cancelled. One check covers
     * every turn on the pod (see {@link #pollOnce}), so the Redis cost is one script call on the
     * shared connection per interval however many turns are streaming, and nothing at all while no
     * turn is streaming.
     *
     * <p>Worst-case time from the cancel flag being written to the turn being cancelled: this
     * interval, plus one Redis round trip, plus the hand-off to the turn's stream. With the default
     * that is 500 ms plus a round trip, the same bound as the per-turn 500 ms poll it replaced
     * ({@code ChatTurnCancelServiceTest#aCancelIsObservedWithinOnePollIntervalPlusTheCheck} only
     * asserts a loose upper bound against a mocked check; real network latency is not measured).
     */
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

    /** {@code DEL}s every key and returns the per-key counts, in order. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DELETE_ALL_SCRIPT =
            new DefaultRedisScript<>(
                    "local r = {} for i, k in ipairs(KEYS) do r[i] = redis.call('DEL', k) end"
                            + " return r",
                    List.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration pollInterval;
    private final RedisScriptRefusal scriptRefusal = new RedisScriptRefusal();

    /**
     * Watched flag key to the turns waiting on it (normally one; two turns racing on one session
     * share a key).
     */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Sinks.One<Long>>> watchers =
            new ConcurrentHashMap<>();

    private final Object pollerLock = new Object();
    private ScheduledExecutorService poller;

    @Autowired
    public ChatTurnCancelService(
            StringRedisTemplate redisTemplate,
            @Value("${app.chat.cancel-poll-interval:500ms}") Duration pollInterval) {
        this.redisTemplate = redisTemplate;
        this.pollInterval =
                pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                        ? DEFAULT_POLL_INTERVAL
                        : pollInterval;
    }

    public ChatTurnCancelService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_POLL_INTERVAL);
    }

    /**
     * Records the cancel request. Returns false when Redis was unreachable (logged, not thrown):
     * the caller still answers 202 - the same "a lost signal is not the caller's error" contract
     * {@code WorkflowRunCancellationService#requestCancellation} documents.
     */
    public boolean requestCancellation(UUID agentId, String sessionId) {
        try {
            redisTemplate.opsForValue().set(key(agentId, sessionId), "1", FLAG_TTL);
            return true;
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "Could not record cancel request for chat turn agent {} session {} - "
                            + "the turn will run to completion.",
                    agentId,
                    sessionId,
                    e);
            return false;
        }
    }

    /**
     * The per-turn watcher: emits once, the moment the cancel flag for this session is observed,
     * and never otherwise. Wired as {@code takeUntilOther} on the turn's stream, so flag
     * observation cancels upstream (firing the partial-persist {@code doOnCancel}) while the client
     * sees a normal stream end.
     *
     * <p>Subscribing registers the turn with the pod's single poller; completing or cancelling the
     * subscription unregisters it. The check is delete-on-read: consuming the flag is what keeps a
     * cancel clicked on turn N from also killing turn N+1 if the operator is faster than the poll.
     *
     * <p>The signal is delivered on the bounded-elastic scheduler, not on the poller thread:
     * cancelling the turn runs its partial-persist (Redis I/O), which must neither stall other
     * turns' cancel checks nor run on a Reactor parallel thread.
     */
    public Flux<Long> watchForCancellation(UUID agentId, String sessionId) {
        return Flux.defer(
                () -> {
                    String key = key(agentId, sessionId);
                    Sinks.One<Long> sink = Sinks.one();
                    watchers.compute(
                            key,
                            (k, queue) -> {
                                ConcurrentLinkedQueue<Sinks.One<Long>> q =
                                        queue == null ? new ConcurrentLinkedQueue<>() : queue;
                                q.add(sink);
                                return q;
                            });
                    ensurePollerStarted();
                    return sink.asMono()
                            .publishOn(TenantAwareSchedulers.boundedElastic())
                            .flux()
                            .doFinally(
                                    signal ->
                                            watchers.computeIfPresent(
                                                    key,
                                                    (k, queue) -> {
                                                        queue.remove(sink);
                                                        return queue.isEmpty() ? null : queue;
                                                    }));
                });
    }

    /**
     * One check for every turn on this pod: one Lua call that {@code DEL}s every watched key (one
     * command, on the shared connection), where a reply of 1 means a cancel flag existed and has
     * now been consumed. Each consumed flag cancels exactly one waiting turn. Redis blips read as
     * "no flag" - a turn must never die because the control channel hiccupped (same fail-open
     * posture as the workflow cancel flag).
     *
     * <p>Package-private so the tests can drive it without waiting for the timer.
     */
    void pollOnce() {
        List<String> keys = new ArrayList<>();
        watchers.forEach(
                (key, queue) -> {
                    if (!queue.isEmpty()) {
                        keys.add(key);
                    }
                });
        if (keys.isEmpty()) {
            return;
        }
        try {
            List<?> replies = deleteAll(keys);
            for (int i = 0; replies != null && i < keys.size() && i < replies.size(); i++) {
                if (replies.get(i) instanceof Number n && n.longValue() > 0) {
                    fire(keys.get(i));
                }
            }
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "Could not check cancel flags for {} in-flight chat turn keys - treating them"
                            + " as not cancelled.",
                    keys.size(),
                    e);
        }
    }

    /**
     * One Lua call on the template's shared connection that {@code DEL}s every key and returns the
     * per-key counts. (A pipelined {@code executePipelined} was tried first: Spring Data Redis
     * opens a dedicated connection for it, so every poll paid a connect and handshake.) If Redis
     * REFUSES the script before running it (scripting disabled, ACL, keys in different Cluster
     * slots) it falls back to one plain {@code DEL} per key, the pre-batching behaviour; any other
     * failure (timeout, connection) propagates and reads as "no flag" for this tick.
     */
    private List<?> deleteAll(List<String> keys) {
        if (scriptRefusal.skipScript()) {
            return deleteOneByOne(keys);
        }
        try {
            return redisTemplate.execute(DELETE_ALL_SCRIPT, keys);
        } catch (RuntimeException e) {
            scriptRefusal.rethrowUnlessRefused(e);
            REDIS_DOWN_LOG.warnf(
                    "Redis refused the batched cancel-flag script - checking flags one key at a"
                            + " time for a few minutes",
                    e);
            return deleteOneByOne(keys);
        }
    }

    private List<Long> deleteOneByOne(List<String> keys) {
        List<Long> replies = new ArrayList<>(keys.size());
        for (String key : keys) {
            replies.add(Boolean.TRUE.equals(redisTemplate.delete(key)) ? 1L : 0L);
        }
        return replies;
    }

    private void fire(String key) {
        ConcurrentLinkedQueue<Sinks.One<Long>> queue = watchers.get(key);
        Sinks.One<Long> sink = queue == null ? null : queue.poll();
        if (sink != null) {
            sink.tryEmitValue(0L);
        }
    }

    private void ensurePollerStarted() {
        synchronized (pollerLock) {
            if (poller != null) {
                return;
            }
            poller =
                    TenantAwareExecutors.newSingleThreadScheduledExecutor("chat-cancel-poller", true);
            long millis = pollInterval.toMillis();
            // Started lazily from the first turn's thread, but the poller serves every tenant: run
            // it as a platform task (no tenant, pin or MDC), never with that first turn's state.
            poller.scheduleWithFixedDelay(
                    TenantAwareExecutors.platform(() -> {
                        try {
                            pollOnce();
                        } catch (Throwable t) {
                            // a scheduled task that throws is silently cancelled for good; never
                            // let that happen
                            log.warn("Cancel poll tick failed", t);
                        }
                    }),
                    millis,
                    millis,
                    TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (pollerLock) {
            if (poller != null) {
                poller.shutdownNow();
                poller = null;
            }
        }
    }

    private static String key(UUID agentId, String sessionId) {
        return KEY_PREFIX + agentId + ":" + sessionId;
    }
}
