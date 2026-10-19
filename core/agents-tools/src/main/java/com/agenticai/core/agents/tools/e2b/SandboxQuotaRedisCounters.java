package com.agenticai.core.agents.tools.e2b;

import com.agenticai.core.tenancy.replica.BoundedRedisCall;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Shared (Redis) daily counters behind {@link PlatformSandboxRunQuota} when {@code
 * app.sandbox.platform-e2b.quota-store=redis}, so the per-tenant and platform-wide caps on
 * platform-paid E2B runs hold across core replicas instead of being multiplied by the replica
 * count.
 *
 * <p>Keys {@code sandbox:e2b-quota:<utc-day>:<tenantId>} and {@code
 * sandbox:e2b-quota:<utc-day>:_global}, each an integer that expires after two days (the UTC day in
 * the key does the rollover, the TTL only bounds memory). The decision is {@code INCR} then
 * compare: the increment is atomic, so two replicas racing the last allowed run cannot both pass
 * it; the loser decrements its own increment again.
 *
 * <p>Fail safe: every Redis problem returns {@link Outcome#UNAVAILABLE} (logged once) and the
 * caller falls back to its per-JVM counters, which is exactly today's behaviour. This class is only
 * on the {@code run_code} tool path (one or two Redis calls per platform-paid run), never on the
 * plain chat path.
 *
 * <p>Every operation is bounded to {@link #CALL_TIMEOUT_MILLIS} ms in total ({@link
 * BoundedRedisCall}; Lettuce's own command timeout is 60 s, which would stall the user's tool call
 * on a black-holed connection). Increments that were already applied when a later command fails are
 * compensated best-effort before answering UNAVAILABLE, so the same run is not counted both here
 * and in the caller's per-JVM counters. If Redis is so broken that the compensation also fails, the
 * leak is one run and ends with the key's TTL.
 */
final class SandboxQuotaRedisCounters {

    private static final Logger log = LoggerFactory.getLogger(SandboxQuotaRedisCounters.class);

    static final String KEY_PREFIX = "sandbox:e2b-quota:";
    static final String GLOBAL_SEGMENT = "_global";
    static final Duration KEY_TTL = Duration.ofDays(2);

    /**
     * Total budget for one consume/refund/read (up to five commands, each normally well under a
     * millisecond).
     */
    static final long CALL_TIMEOUT_MILLIS = 500L;

    enum Outcome {
        ALLOWED,
        TENANT_LIMIT,
        GLOBAL_LIMIT,
        UNAVAILABLE
    }

    private final StringRedisTemplate redis;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final BoundedRedisCall bounded = new BoundedRedisCall("sandbox-quota-redis");

    SandboxQuotaRedisCounters(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String tenantKey(LocalDate day, String tenantId) {
        return KEY_PREFIX + day + ":" + tenantId;
    }

    static String globalKey(LocalDate day) {
        return KEY_PREFIX + day + ":" + GLOBAL_SEGMENT;
    }

    /**
     * Counts one run: tenant first, then the platform ceiling; a refused attempt leaves both
     * counters unchanged.
     */
    Outcome consume(LocalDate day, String tenantId, int tenantCap, int globalCap) {
        String tenantKey = tenantKey(day, tenantId);
        String globalKey = globalKey(day);
        // Which increments are still applied: lets the catch block undo exactly those (see the
        // class comment).
        Applied applied = new Applied();
        try {
            return bounded.call(
                    CALL_TIMEOUT_MILLIS,
                    () -> consumeNow(tenantKey, globalKey, tenantCap, globalCap, applied));
        } catch (RuntimeException e) {
            compensate(tenantKey, globalKey, applied);
            return unavailable(e);
        }
    }

    private Outcome consumeNow(
            String tenantKey, String globalKey, int tenantCap, int globalCap, Applied applied) {
        Long used = redis.opsForValue().increment(tenantKey);
        if (used == null) {
            return unavailable(null);
        }
        applied.tenant = true;
        redis.expire(tenantKey, KEY_TTL);
        if (used > tenantCap) {
            redis.opsForValue().decrement(tenantKey);
            applied.tenant = false;
            return Outcome.TENANT_LIMIT;
        }
        Long global = redis.opsForValue().increment(globalKey);
        if (global == null) {
            redis.opsForValue().decrement(tenantKey);
            applied.tenant = false;
            return unavailable(null);
        }
        applied.global = true;
        redis.expire(globalKey, KEY_TTL);
        if (global > globalCap) {
            redis.opsForValue().decrement(globalKey);
            applied.global = false;
            redis.opsForValue().decrement(tenantKey);
            applied.tenant = false;
            return Outcome.GLOBAL_LIMIT;
        }
        return Outcome.ALLOWED;
    }

    /**
     * Best effort, never throws: undoes the increments {@link #consumeNow} had applied when
     * something failed.
     */
    private void compensate(String tenantKey, String globalKey, Applied applied) {
        if (!applied.tenant && !applied.global) {
            return;
        }
        try {
            bounded.run(
                    CALL_TIMEOUT_MILLIS,
                    () -> {
                        if (applied.global) {
                            redis.opsForValue().decrement(globalKey);
                        }
                        if (applied.tenant) {
                            redis.opsForValue().decrement(tenantKey);
                        }
                    });
        } catch (RuntimeException ignored) {
            // Redis is unusable; the leaked unit expires with the key.
        }
    }

    /**
     * Gives one run back; false when Redis could not be used (the caller then refunds its local
     * counter).
     */
    boolean refund(LocalDate day, String tenantId) {
        try {
            bounded.run(
                    CALL_TIMEOUT_MILLIS,
                    () -> {
                        giveBack(tenantKey(day, tenantId));
                        giveBack(globalKey(day));
                    });
            return true;
        } catch (RuntimeException e) {
            unavailable(e);
            return false;
        }
    }

    /** Runs counted today for the tenant, or null when Redis could not be used. */
    Integer used(LocalDate day, String tenantId) {
        try {
            String raw =
                    bounded.call(
                            CALL_TIMEOUT_MILLIS,
                            () -> redis.opsForValue().get(tenantKey(day, tenantId)));
            if (raw == null) {
                return 0;
            }
            return Integer.parseInt(raw.strip());
        } catch (RuntimeException e) {
            unavailable(e);
            return null;
        }
    }

    /** DECR, but never leave a negative counter behind (an expired or never-written key). */
    private void giveBack(String key) {
        Long left = redis.opsForValue().decrement(key);
        if (left != null && left < 0) {
            redis.opsForValue().increment(key);
        }
    }

    /**
     * Which increments of one {@link #consume} are still applied; written by the pool thread, read
     * by the caller.
     */
    private static final class Applied {
        volatile boolean tenant;
        volatile boolean global;
    }

    private Outcome unavailable(RuntimeException cause) {
        if (warned.compareAndSet(false, true)) {
            log.warn(
                    "Platform sandbox quota: Redis unavailable, falling back to per-JVM counters (the cap is "
                            + "multiplied by the replica count until Redis is back)",
                    cause);
        }
        return Outcome.UNAVAILABLE;
    }
}
