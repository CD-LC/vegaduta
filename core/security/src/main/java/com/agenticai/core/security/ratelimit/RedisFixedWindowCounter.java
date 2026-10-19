package com.agenticai.core.security.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Shared fixed-window counter for Redis-backed rate limiters (security audit
 * 2026-10).
 *
 * <p>Every limiter in core hand-rolled {@code INCR} then {@code EXPIRE} on the
 * first hit. Those are two round trips: if the {@code EXPIRE} is lost (timeout,
 * failover, a pod killed between the calls) the key has no TTL, so that IP's
 * counter only ever grows and the caller is locked out for good. Doing both in
 * one Lua script makes the window atomic.
 *
 * <p>When Redis is absent or erroring, this falls back to
 * {@link LocalRateLimitFallback} - never to "allowed". A Redis blip must not
 * be the moment the abuse controls switch off.
 */
public final class RedisFixedWindowCounter {

    /**
     * After one Redis failure, skip Redis outright for this long. Rate
     * limiters run on every request; without this a dead store taxes each
     * call with a connect timeout before the local fallback engages. A live
     * store that blips once only loses Redis counting for the window, which
     * is what {@link LocalRateLimitFallback} exists to cover.
     */
    private static final long REDIS_DOWN_RETRY_MILLIS = 10_000;

    private static final AtomicLong REDIS_DOWN_UNTIL = new AtomicLong();

    private static boolean redisProbablyDown() {
        return System.currentTimeMillis() < REDIS_DOWN_UNTIL.get();
    }

    private static void noteRedisFailure() {
        REDIS_DOWN_UNTIL.set(System.currentTimeMillis() + REDIS_DOWN_RETRY_MILLIS);
    }

    /** Test hook - the flag is process-wide, like the fallback table. */
    static void resetRedisDownFlag() {
        REDIS_DOWN_UNTIL.set(0);
    }

    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of(
        "local c = redis.call('INCR', KEYS[1]) "
            + "if c == 1 or redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "return c",
        Long.class
    );

    /** Same window, but returns {count, seconds until the window resets}. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> INCR_WITH_TTL_AND_RESET = RedisScript.of(
        "local c = redis.call('INCR', KEYS[1]) "
            + "if c == 1 or redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "return {c, redis.call('TTL', KEYS[1])}",
        List.class
    );

    /**
     * One counted hit: the count so far in this window (including this hit), the limit, and the
     * seconds until the window resets. Enough to answer the IETF {@code RateLimit} header fields.
     */
    public record Usage(long count, long limit, long resetSeconds) {
        public boolean allowed() {
            return count <= limit;
        }

        public long remaining() {
            return Math.max(0, limit - count);
        }
    }

    private RedisFixedWindowCounter() {
    }

    /** {@link #tryAcquire} that also reports usage; same Redis-then-local fallback. */
    public static Usage acquire(StringRedisTemplate redis, String key, long maxPerWindow, Duration window) {
        long windowSeconds = Math.max(1, window.toSeconds());
        if (redis != null && !redisProbablyDown()) {
            try {
                List<?> r = redis.execute(INCR_WITH_TTL_AND_RESET, List.of(key), String.valueOf(windowSeconds));
                if (r != null && r.size() == 2 && r.get(0) instanceof Number c && r.get(1) instanceof Number ttl) {
                    long reset = ttl.longValue() > 0 ? ttl.longValue() : windowSeconds;
                    REDIS_DOWN_UNTIL.set(0);
                    return new Usage(c.longValue(), maxPerWindow, reset);
                }
            } catch (RuntimeException e) {
                // fall through to the in-process counter
            }
            noteRedisFailure();
        }
        return LocalRateLimitFallback.acquire(key, maxPerWindow, window);
    }

    /**
     * Counts one hit against {@code key} and reports whether it is within
     * {@code maxPerWindow}. {@code redis} may be null (no Redis configured).
     */
    public static boolean tryAcquire(StringRedisTemplate redis, String key, long maxPerWindow, Duration window) {
        if (redis != null && !redisProbablyDown()) {
            try {
                Long count = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(Math.max(1, window.toSeconds())));
                if (count != null) {
                    REDIS_DOWN_UNTIL.set(0);
                    return count <= maxPerWindow;
                }
            } catch (RuntimeException e) {
                // fall through to the in-process counter
            }
            noteRedisFailure();
        }
        return LocalRateLimitFallback.tryAcquire(key, maxPerWindow, window);
    }
}
