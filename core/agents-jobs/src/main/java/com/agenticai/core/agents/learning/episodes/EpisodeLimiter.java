package com.agenticai.core.agents.learning.episodes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Rolling-window caps on episode proposals: per agent per hour, per agent and user per hour, per agent per day. In
 * process and bounded (least recently used keys are forgotten), so a restart or an evicted key only makes the cap more
 * lenient for one window; the store's own pending cap is the hard bound. A permit is consumed only when all three
 * windows have room.
 */
final class EpisodeLimiter {

    enum Verdict {
        OK,
        AGENT_HOUR,
        USER_HOUR,
        AGENT_DAY
    }

    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24 * HOUR_MS;
    private static final int MAX_KEYS = 5_000;

    private final int perAgentHour;
    private final int perUserHour;
    private final int perAgentDay;
    private final LongSupplier clock;

    private final Map<String, Deque<Long>> windows = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<Long>> eldest) {
            return size() > MAX_KEYS;
        }
    };

    EpisodeLimiter(int perAgentHour, int perUserHour, int perAgentDay, LongSupplier clock) {
        this.perAgentHour = perAgentHour;
        this.perUserHour = perUserHour;
        this.perAgentDay = perAgentDay;
        this.clock = clock;
    }

    synchronized Verdict tryAcquire(UUID agentId, String user) {
        long now = clock.getAsLong();
        String agentKey = "a:" + agentId;
        String userKey = "u:" + agentId + ":" + (user == null ? "" : user);
        Deque<Long> day = window(agentKey, now, DAY_MS);
        Deque<Long> userHour = window(userKey, now, HOUR_MS);
        if (count(day, now - HOUR_MS) >= perAgentHour) {
            return Verdict.AGENT_HOUR;
        }
        if (userHour.size() >= perUserHour) {
            return Verdict.USER_HOUR;
        }
        if (day.size() >= perAgentDay) {
            return Verdict.AGENT_DAY;
        }
        day.addLast(now);
        userHour.addLast(now);
        return Verdict.OK;
    }

    private Deque<Long> window(String key, long now, long span) {
        Deque<Long> d = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!d.isEmpty() && d.peekFirst() <= now - span) {
            d.pollFirst();
        }
        return d;
    }

    private static int count(Deque<Long> d, long since) {
        int n = 0;
        for (long t : d) {
            if (t > since) {
                n++;
            }
        }
        return n;
    }
}
