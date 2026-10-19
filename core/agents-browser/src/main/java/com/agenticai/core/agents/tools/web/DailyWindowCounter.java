package com.agenticai.core.agents.tools.web;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A Redis fixed-window counter: the window is 24 hours from the first write of a key. Shared by the
 * research-run cap and the deep_research search allowance so both get the same expiry handling.
 *
 * <p>Never throws: a cache outage must not take a feature down. By default every method then
 * answers "nothing counted" (0), i.e. fail-open. A counter built with {@code localFallback = true}
 * (the paid-spend caps: research runs and deep_research searches) instead keeps counting in this
 * JVM while Redis is unavailable, so an outage degrades to a per-replica cap rather than to no cap
 * at all.
 *
 * <p>The expiry is not atomic with the increment (two commands), so it is self-healing instead:
 * every positive write that is not the first of its window checks that the key still has a
 * time-to-live and sets one if it does not. A key can therefore never be left counting forever
 * because one EXPIRE failed.
 */
public final class DailyWindowCounter {

    private static final Logger log = LoggerFactory.getLogger(DailyWindowCounter.class);
    static final Duration WINDOW = Duration.ofDays(1);

    private final ObjectProvider<StringRedisTemplate> redis;
    private final boolean localFallback;
    private final java.util.concurrent.ConcurrentHashMap<String, LocalWindow> local =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** One in-process 24h window; {@code count} is guarded by the instance. */
    private static final class LocalWindow {
        final long startedAtNanos = System.nanoTime();
        long count;

        boolean expired() {
            return System.nanoTime() - startedAtNanos >= WINDOW.toNanos();
        }
    }

    public DailyWindowCounter(ObjectProvider<StringRedisTemplate> redis) {
        this(redis, false);
    }

    /**
     * @param localFallback keep counting in-process while Redis is absent or failing, instead of
     *     failing open
     */
    public DailyWindowCounter(ObjectProvider<StringRedisTemplate> redis, boolean localFallback) {
        this.redis = redis;
        this.localFallback = localFallback;
    }

    private long localCurrent(String key) {
        LocalWindow w = local.get(key);
        if (w == null) {
            return 0;
        }
        synchronized (w) {
            return w.expired() ? 0 : Math.max(0, w.count);
        }
    }

    private long localAdd(String key, long by) {
        if (local.size() > 10_000) {
            local.values().removeIf(LocalWindow::expired);
        }
        while (true) {
            LocalWindow w = local.computeIfAbsent(key, k -> new LocalWindow());
            synchronized (w) {
                if (w.expired()) {
                    local.remove(key, w);
                    continue;
                }
                w.count = Math.max(0, w.count + by);
                return w.count;
            }
        }
    }

    /**
     * Current count of the window, 0 when there is none, Redis is away or the value is unreadable.
     */
    public long current(String key) {
        try {
            StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
            if (template == null) {
                return localFallback ? localCurrent(key) : 0;
            }
            String value = template.opsForValue().get(key);
            return value == null ? 0 : Math.max(0, Long.parseLong(value.trim()));
        } catch (RuntimeException e) {
            log.debug(
                    "Daily counter read failed - using the {} count",
                    localFallback ? "in-process" : "zero",
                    e);
            return localFallback ? localCurrent(key) : 0;
        }
    }

    /**
     * Adds {@code by} (positive) to the window and makes sure the key expires.
     *
     * @return the new count, or 0 when nothing could be counted (fail-open, no local fallback)
     */
    public long add(String key, long by) {
        if (by <= 0) {
            return current(key);
        }
        try {
            StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
            if (template == null) {
                return localFallback ? localAdd(key, by) : 0;
            }
            Long count = template.opsForValue().increment(key, by);
            if (count == null) {
                return localFallback ? localAdd(key, by) : 0;
            }
            ensureExpiry(template, key, count == by);
            return count;
        } catch (RuntimeException e) {
            if (localFallback) {
                log.warn(
                        "Daily counter write failed - counting in-process until Redis is back: {}",
                        e.toString());
                return localAdd(key, by);
            }
            log.debug("Daily counter write failed - not counting", e);
            return 0;
        }
    }

    /**
     * Takes {@code by} back off a key this caller has JUST added to (so the key is known to exist).
     */
    public void undo(String key, long by) {
        decrement(key, by, false);
    }

    /**
     * Gives {@code by} back some time after it was added. Skipped when the window has expired in
     * the meantime: decrementing a missing key would recreate it below zero with no expiry.
     */
    public void giveBack(String key, long by) {
        decrement(key, by, true);
    }

    private void decrement(String key, long by, boolean onlyIfPresent) {
        if (by <= 0) {
            return;
        }
        if (localFallback && localCurrent(key) > 0) {
            // The units being given back were counted in-process (Redis was away when they were
            // added).
            localAdd(key, -by);
            return;
        }
        try {
            StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
            if (template == null) {
                if (localFallback) {
                    localAdd(key, -by);
                }
                return;
            }
            if (onlyIfPresent && !Boolean.TRUE.equals(template.hasKey(key))) {
                return;
            }
            template.opsForValue().increment(key, -by);
        } catch (RuntimeException e) {
            if (localFallback) {
                localAdd(key, -by);
            }
            log.debug("Daily counter give-back failed", e);
        }
    }

    /** Never throws: a failed expiry is retried by the next write (see class javadoc). */
    private static void ensureExpiry(StringRedisTemplate template, String key, boolean firstWrite) {
        try {
            if (firstWrite) {
                template.expire(key, WINDOW);
                return;
            }
            Long ttlSeconds = template.getExpire(key);
            // -1 = the key exists with no expiry (an earlier EXPIRE was lost).
            if (ttlSeconds != null && ttlSeconds == -1L) {
                template.expire(key, WINDOW);
            }
        } catch (RuntimeException e) {
            log.debug("Daily counter expiry not set for this write - the next write retries", e);
        }
    }
}
