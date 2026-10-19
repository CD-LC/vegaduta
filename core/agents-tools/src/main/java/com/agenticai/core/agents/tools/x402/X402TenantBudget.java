package com.agenticai.core.agents.tools.x402;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Per-tenant x402 spend ceiling over a rolling 24 hours (security review SEC-029). Spend is tracked
 * in hourly buckets; a payment is allowed when the sum of the current and the previous 23 buckets
 * plus the new amount stays within the budget - a rolling window at one-hour granularity.
 *
 * <p><b>Storage:</b> Redis when a {@link StringRedisTemplate} is available, so every core replica
 * shares one counter; otherwise an in-memory map per node (a multi-replica deployment without Redis
 * then allows up to N x budget per tenant - stated residual). Reservation is optimistic: INCRBY
 * first, sum, and give the amount back when over budget, so two concurrent payments can both be
 * refused but never both be allowed past the ceiling. Any Redis failure refuses the payment (fail
 * closed).
 */
final class X402TenantBudget {

    private static final Logger log = LoggerFactory.getLogger(X402TenantBudget.class);
    private static final int WINDOW_HOURS = 24;
    private static final Duration KEY_TTL = Duration.ofHours(WINDOW_HOURS + 1);

    private final long budgetAtomic;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Map<String, TreeMap<Long, Long>> memory = new ConcurrentHashMap<>();

    X402TenantBudget(long budgetAtomic, StringRedisTemplate redis, Clock clock) {
        this.budgetAtomic = Math.max(0, budgetAtomic);
        this.redis = redis;
        this.clock = clock;
    }

    long budgetAtomic() {
        return budgetAtomic;
    }

    /**
     * Reserves {@code amount} for {@code tenantId}; false when that would exceed the rolling-day
     * budget.
     */
    boolean tryReserve(String tenantId, long amount) {
        if (tenantId == null || tenantId.isBlank() || amount <= 0 || amount > budgetAtomic) {
            return false;
        }
        long hour = clock.instant().getEpochSecond() / 3600;
        return redis != null
                ? reserveRedis(tenantId, hour, amount)
                : reserveMemory(tenantId, hour, amount);
    }

    /** Gives back a reservation whose payment was never produced (signing failed). */
    void release(String tenantId, long amount) {
        if (tenantId == null || amount <= 0) {
            return;
        }
        long hour = clock.instant().getEpochSecond() / 3600;
        if (redis != null) {
            try {
                redis.opsForValue().decrement(key(tenantId, hour), amount);
            } catch (RuntimeException e) {
                log.warn(
                        "x402 budget release failed for tenant {} - budget stays consumed (safe direction)",
                        tenantId);
            }
            return;
        }
        TreeMap<Long, Long> buckets = memory.get(tenantId);
        if (buckets != null) {
            synchronized (buckets) {
                buckets.computeIfPresent(hour, (h, v) -> Math.max(0, v - amount));
            }
        }
    }

    private boolean reserveMemory(String tenantId, long hour, long amount) {
        TreeMap<Long, Long> buckets = memory.computeIfAbsent(tenantId, t -> new TreeMap<>());
        synchronized (buckets) {
            buckets.headMap(hour - WINDOW_HOURS + 1).clear();
            long spent = 0;
            for (long v : buckets.values()) {
                spent += v;
            }
            if (spent + amount > budgetAtomic) {
                return false;
            }
            buckets.merge(hour, amount, Long::sum);
            return true;
        }
    }

    private boolean reserveRedis(String tenantId, long hour, long amount) {
        String current = key(tenantId, hour);
        boolean incremented = false;
        try {
            redis.opsForValue().increment(current, amount);
            incremented = true;
            redis.expire(current, KEY_TTL);
            List<String> keys = new ArrayList<>(WINDOW_HOURS);
            for (int i = 0; i < WINDOW_HOURS; i++) {
                keys.add(key(tenantId, hour - i));
            }
            List<String> values = redis.opsForValue().multiGet(keys);
            long spent = 0;
            if (values != null) {
                for (String v : values) {
                    if (v != null && !v.isBlank()) {
                        spent += Long.parseLong(v.strip());
                    }
                }
            }
            if (spent > budgetAtomic) {
                redis.opsForValue().decrement(current, amount);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn(
                    "x402 budget check failed for tenant {} - refusing the payment: {}",
                    tenantId,
                    e.toString());
            if (incremented) {
                try {
                    redis.opsForValue().decrement(current, amount);
                } catch (RuntimeException ignored) {
                    // Budget stays consumed - the safe direction.
                }
            }
            return false;
        }
    }

    private static String key(String tenantId, long hour) {
        return "x402:spend:" + tenantId + ":" + hour;
    }
}
