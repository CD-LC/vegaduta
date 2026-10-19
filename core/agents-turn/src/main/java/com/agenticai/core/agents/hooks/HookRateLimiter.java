package com.agenticai.core.agents.hooks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Counters behind the {@code limit} action.
 *
 * <ul>
 *   <li>{@link #tryAcquire} - a sliding window per key (tenant, agent, hook), for "per minute".</li>
 *   <li>{@link RunCounters} - one tiny object per run, for "per run".</li>
 * </ul>
 *
 * <p>In-process state: the platform runs a single core replica today (see the "core cannot run
 * two replicas" backlog item), so a per-process window is the honest scope of "per minute".
 * With a second replica each would enforce its own window - a looser limit, never a stricter
 * one, so the failure direction is "limit not reached" rather than "tool wrongly refused".
 */
final class HookRateLimiter {

    static final long MINUTE_MILLIS = 60_000L;
    private static final int MAX_KEYS = 5_000;

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;

    HookRateLimiter() {
        this(System::currentTimeMillis);
    }

    HookRateLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Records one use and returns true, or returns false (recording nothing) when the window is full. */
    boolean tryAcquire(String key, int max, long windowMillis) {
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        long now = clockMillis.getAsLong();
        boolean acquired;
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= windowMillis) {
                q.pollFirst();
            }
            acquired = q.size() < max;
            if (acquired) {
                q.addLast(now);
            }
        }
        if (windows.size() > MAX_KEYS) {
            purge(now, windowMillis);
        }
        return acquired;
    }

    private void purge(long now, long windowMillis) {
        windows.entrySet().removeIf(e -> {
            Deque<Long> q = e.getValue();
            synchronized (q) {
                return q.isEmpty() || now - q.peekLast() >= windowMillis;
            }
        });
    }

    /** Per-run call counts, keyed by hook. Shared by every tool wrapper of one turn. */
    static final class RunCounters {

        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        /** Records one use and returns true, or returns false (recording nothing) when {@code max} is reached. */
        boolean tryIncrement(String key, int max) {
            AtomicInteger c = counts.computeIfAbsent(key, k -> new AtomicInteger());
            while (true) {
                int current = c.get();
                if (current >= max) {
                    return false;
                }
                if (c.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }
    }
}
