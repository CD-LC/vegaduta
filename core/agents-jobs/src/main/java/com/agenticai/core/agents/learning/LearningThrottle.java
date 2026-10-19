package com.agenticai.core.agents.learning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-agent, per-loop cooldown and daily cap, claimed BEFORE a loop makes a (paid) model call. In memory: a
 * restart resets it, which only ever allows a little more work, never more proposals than the pending cap.
 */
final class LearningThrottle {

    private final int cooldownMinutes;
    private final int maxPerDay;
    private final Map<String, Instant> last = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> today = new ConcurrentHashMap<>();

    LearningThrottle(int cooldownMinutes, int maxPerDay) {
        this.cooldownMinutes = Math.max(0, cooldownMinutes);
        this.maxPerDay = Math.max(1, maxPerDay);
    }

    boolean claim(String loop, UUID agentId) {
        String key = loop + ":" + agentId;
        Instant now = Instant.now();
        Instant previous = last.get(key);
        if (previous != null && Duration.between(previous, now).toMinutes() < cooldownMinutes) {
            return false;
        }
        Deque<Instant> window = today.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            Instant dayAgo = now.minus(Duration.ofHours(24));
            while (!window.isEmpty() && window.peekFirst().isBefore(dayAgo)) {
                window.pollFirst();
            }
            if (window.size() >= maxPerDay) {
                return false;
            }
            window.addLast(now);
        }
        last.put(key, now);
        if (last.size() > 5_000) {
            last.entrySet().removeIf(e -> e.getValue().isBefore(now.minus(Duration.ofDays(1))));
            today.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return true;
    }
}
