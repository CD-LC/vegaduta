package com.agenticai.core.guardrails.procedures;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.replica.BoundedRedisCall;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis copy of "which tools have already executed in this chat session" for {@link ProcedureGuardEvaluator}'s
 * {@code REQUIRE_TOOL_BEFORE} rules ({@code app.guardrails.procedures.session-calls-store=redis}).
 *
 * <p>Without it the set lives in one JVM, so with two core replicas a turn that lands on the other replica sees
 * no history and DENIES a tool whose prerequisite already ran (wrong, but on the safe side). One Redis set per
 * session: {@code procedure:calls:<tenantId>:<sessionId>}, members are tool names, TTL 24h (the chat session
 * TTL), refreshed on every write.
 *
 * <ul>
 *   <li>Writes are asynchronous on one bounded daemon thread: nothing on the tool-call path waits on Redis. A full
 *       queue drops the write (WARN once): that call is then only known to this replica, i.e. today's behaviour.</li>
 *   <li>The read happens ONLY when a {@code REQUIRE_TOOL_BEFORE} check would otherwise deny (local memory has no
 *       record of the prerequisite), so the common allow path never touches Redis.</li>
 *   <li>Any Redis failure answers "no record" (fail closed, exactly today's result) and logs once. The read is
 *       bounded to {@link BoundedRedisCall#DEFAULT_TIMEOUT_MILLIS} ms, so a hung connection costs at most that
 *       (then the breaker answers "no record" immediately for a few seconds), never Lettuce's 60 s.</li>
 * </ul>
 */
final class ProcedureSessionCallsRedis {

    private static final Logger log = LoggerFactory.getLogger(ProcedureSessionCallsRedis.class);

    static final String KEY_PREFIX = "procedure:calls:";
    static final Duration TTL = Duration.ofHours(24);
    private static final int QUEUE_CAPACITY = 256;

    private final StringRedisTemplate redis;
    private final ThreadPoolExecutor writer;
    /** The read runs on a tool-call path, so it is time-bounded (Lettuce's own command timeout is 60 s). */
    private final BoundedRedisCall reads = new BoundedRedisCall("procedure-session-calls-read");
    private final AtomicBoolean warned = new AtomicBoolean();

    ProcedureSessionCallsRedis(StringRedisTemplate redis) {
        this.redis = redis;
        this.writer = TenantAwareExecutors.newThreadPool(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), TenantAwareExecutors.threadFactory("procedure-session-calls", true));
        this.writer.allowCoreThreadTimeOut(true);
    }

    static String key(String tenant, String sessionId) {
        return KEY_PREFIX + tenant + ":" + sessionId;
    }

    /** Queues "tool ran in this session"; never blocks and never throws. */
    void recordAsync(String tenant, String sessionId, String toolName) {
        try {
            writer.execute(() -> recordNow(tenant, sessionId, toolName));
        } catch (RejectedExecutionException e) {
            warnOnce("write queue full, the call is only known to this replica", null);
        }
    }

    /** The write itself; package-private so tests can run it inline. */
    void recordNow(String tenant, String sessionId, String toolName) {
        String key = key(tenant, sessionId);
        try {
            redis.opsForSet().add(key, toolName);
            redis.expire(key, TTL);
        } catch (RuntimeException e) {
            warnOnce("could not record an executed tool in Redis", e);
        }
    }

    /** True only when Redis positively says {@code toolName} ran in the session; any failure is "no record". */
    boolean hasRun(String tenant, String sessionId, String toolName) {
        try {
            String key = key(tenant, sessionId);
            return Boolean.TRUE.equals(reads.call(BoundedRedisCall.DEFAULT_TIMEOUT_MILLIS,
                () -> redis.opsForSet().isMember(key, toolName)));
        } catch (RuntimeException e) {
            warnOnce("could not read executed tools from Redis", e);
            return false;
        }
    }

    /** Test seam: returns once every write queued so far has run (the writer is a single FIFO thread). */
    void awaitQueuedWrites() throws Exception {
        writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    void shutdown() {
        writer.shutdownNow();
        reads.shutdown();
    }

    private void warnOnce(String what, RuntimeException cause) {
        if (warned.compareAndSet(false, true)) {
            log.warn("Procedure guard session history: {} - falling back to this replica's memory only", what, cause);
        }
    }
}
