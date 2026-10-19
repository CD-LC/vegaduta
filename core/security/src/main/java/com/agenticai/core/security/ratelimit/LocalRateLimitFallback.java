package com.agenticai.core.security.ratelimit;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process stand-in for a Redis rate-limit counter, used ONLY while Redis is
 * unreachable (security audit 2026-09-18).
 *
 * <p>Every Redis-backed limiter in core used to answer "allowed" when Redis
 * errored - signup, device claim, share pages, the demo, the LLM gateway, all at
 * once. That makes a Redis blip (or an attacker who can cause one) the moment
 * every abuse control on the platform switches itself off. Failing CLOSED is no
 * better: it turns the same blip into a full sign-up and demo outage. This class
 * is the third option - keep limiting, per JVM, until Redis is back.
 *
 * <p>It is deliberately weaker than the Redis path and that is fine for a
 * degraded mode: counts are per replica rather than shared (core runs one
 * replica today), windows are fixed rather than sliding, and nothing survives a
 * restart. The map is bounded; when it fills, expired windows are swept, and if
 * it is STILL full the call is refused - an attacker rotating keys to blow the
 * table must not be able to buy themselves an open door.
 */
public final class LocalRateLimitFallback {

    private static final int MAX_TRACKED_KEYS = 50_000;
    private static final ConcurrentHashMap<String, Window> WINDOWS = new ConcurrentHashMap<>();

    private LocalRateLimitFallback() {}

    private record Window(long startedAtMillis, long count, long windowMillis) {}

    /**
     * Counts this call against {@code key} and says whether it is still within
     * {@code maxPerWindow}. Same contract as the limiters' own tryAcquire.
     */
    public static boolean tryAcquire(String key, long maxPerWindow, Duration window) {
        return acquire(key, maxPerWindow, window).allowed();
    }

    /** {@link #tryAcquire} that also reports the count and the seconds left in the window. */
    public static RedisFixedWindowCounter.Usage acquire(String key, long maxPerWindow, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1L, window.toMillis());
        if (WINDOWS.size() >= MAX_TRACKED_KEYS && !WINDOWS.containsKey(key)) {
            WINDOWS.entrySet().removeIf(e -> now - e.getValue().startedAtMillis() >= e.getValue().windowMillis());
            if (WINDOWS.size() >= MAX_TRACKED_KEYS) {
                return new RedisFixedWindowCounter.Usage(maxPerWindow + 1, maxPerWindow, Math.max(1, window.toSeconds()));
            }
        }
        Window updated = WINDOWS.compute(key, (k, current) ->
            current == null || now - current.startedAtMillis() >= windowMillis
                ? new Window(now, 1L, windowMillis)
                : new Window(current.startedAtMillis(), current.count() + 1L, windowMillis)
        );
        long reset = Math.max(1L, (updated.startedAtMillis() + windowMillis - now + 999) / 1000);
        return new RedisFixedWindowCounter.Usage(updated.count(), maxPerWindow, reset);
    }

    /** Test hook - the table is process-wide, so a test that drives a limiter's Redis-down path must start from empty. */
    public static void reset() {
        WINDOWS.clear();
    }
}
