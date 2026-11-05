package com.agenticai.core.security;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Atomic fixed-window usage counters in Redis, shared by every spend/quota control that keeps an
 * integer counter with a window TTL (per-user budget, free-tier tokens, smart-AI feature budget).
 *
 * <p>Two things a plain {@code INCRBY} + {@code EXPIRE} gets wrong, both of which end as free usage
 * or a lockout:
 *
 * <ul>
 *   <li><b>Positive add</b>: the TTL used to be set only when the result equalled the amount just
 *       added. A crash between the two commands left a counter that never expires, and a counter
 *       that came back to exactly {@code by} after a refund had its window restarted. {@link
 *       #INCR_WITH_TTL} sets the expiry whenever the key has none, in the same script as the
 *       increment.
 *   <li><b>Negative add (refund)</b>: a negative {@code INCRBY} on a key whose window already
 *       expired creates a fresh negative counter, which is credit nobody paid for; a refund larger
 *       than what is left drives a live counter below zero. {@link #REFUND_IF_LIVE} drops a refund
 *       against an absent key and clamps at zero keeping the TTL.
 * </ul>
 */
public final class SpendCounters {

    /**
     * Increment, then give the key the window expiry if it has none (negative PTTL). Returns the
     * new total.
     */
    public static final DefaultRedisScript<Long> INCR_WITH_TTL =
            new DefaultRedisScript<>(
                    "local v = redis.call('INCRBY', KEYS[1], ARGV[1]) "
                            + "if redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end "
                            + "return v",
                    Long.class);

    /**
     * Take {@code -by} back out of a live window only; clamp at zero with KEEPTTL. Returns the
     * total after.
     */
    public static final DefaultRedisScript<Long> REFUND_IF_LIVE =
            new DefaultRedisScript<>(
                    "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
                            + "local v = redis.call('INCRBY', KEYS[1], ARGV[1]) "
                            + "if v < 0 then redis.call('SET', KEYS[1], '0', 'KEEPTTL') v = 0 end "
                            + "return v",
                    Long.class);

    private SpendCounters() {}

    /**
     * Adds {@code by} (negative = refund) to the window counter atomically and returns the total
     * afterwards, or {@code null} if Redis returned nothing. Redis errors propagate; callers own
     * the open/closed decision.
     */
    public static Long add(StringRedisTemplate redis, String key, Duration window, long by) {
        if (by < 0) {
            return redis.execute(REFUND_IF_LIVE, List.of(key), Long.toString(by));
        }
        return redis.execute(
                INCR_WITH_TTL,
                List.of(key),
                Long.toString(by),
                Long.toString(Math.max(1L, window.toMillis())));
    }
}
