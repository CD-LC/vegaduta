package com.agenticai.core.agents.budget;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Self-heals per-user budget counters whose TTL was lost (INCRBY then a separate EXPIRE can be
 * interrupted). Extracted unchanged from {@link PerUserBudgetService}, which calls it on the read
 * path and from the daily sweep.
 */
final class PerUserBudgetTtlRepair {

    private static final Logger log = LoggerFactory.getLogger(PerUserBudgetTtlRepair.class);

    private final StringRedisTemplate redisTemplate;

    PerUserBudgetTtlRepair(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Self-heal a counter whose TTL was lost. Both {@code incrementBy} (spend)
     * and {@code creditTopUp} (paid credit) do an {@code INCRBY} followed by a
     * separate {@code EXPIRE} call - if the process dies between the two, or
     * Redis errors on just the second one, the key is left with a value but NO
     * expiry. For the spend counter that is a permanent lockout: {@code checkBudget} compares {@code spent >= cap} forever with no window ever
     * rolling over, and nothing short of a human deleting the Redis key fixes
     * it. For the credit counter it's the opposite bug - unbounded top-up
     * credit that never expires. Both are cost-control bugs; this repairs
     * either shape the same way.
     *
     * <p>Deliberately a read-path repair rather than making the write atomic
     * (e.g. a Lua {@code INCRBY}+{@code EXPIRE} script): {@code checkBudget}
     * runs on every single request for a (tenant, user, modality), including
     * ones that are currently blocked, so the very next read after a lost TTL
     * - regardless of which process or request performs it - repairs the key.
     * That makes this correct even when the process that ran {@code incrementBy} died mid-sequence: the repair does not depend on that same
     * process, or even that same user's next successful call, ever running
     * again - only on the read path being exercised, which a stuck counter by
     * definition keeps happening (that's exactly what "stuck" means here).
     *
     * <p>{@code TTL} returns {@code -1} when a key exists with no expiry set,
     * vs {@code -2} when the key doesn't exist at all - callers of this method
     * have already ruled out the latter by getting a non-null value back from
     * {@code GET} first, so {@code -1} unambiguously means "lost its TTL,
     * needs repair." Best-effort and fail-open like every other read in this
     * class: a Redis error during the repair attempt itself is swallowed and
     * simply retried on the next read.
     */
    boolean repairMissingTtl(String redisKey, int windowSeconds) {
        try {
            Long ttl = redisTemplate.getExpire(redisKey);
            if (ttl != null && ttl == -1L) {
                redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
                log.warn(
                    "Per-user budget counter '{}' had no TTL (would never have reset) - repaired with a fresh {}s expiry",
                    redisKey, windowSeconds);
                return true;
            }
        } catch (RuntimeException e) {
            log.debug("Per-user budget TTL repair failed - leaving for the next read to retry", e);
        }
        return false;
    }

    int repairPrefix(String prefix, int windowSeconds) {
        int repaired = 0;
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions().match(prefix + "*").count(500).build())) {
            while (cursor.hasNext()) {
                if (repairMissingTtl(cursor.next(), windowSeconds)) {
                    repaired++;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Per-user budget TTL repair sweep failed for prefix '{}' - leaving for the next lazy read to retry", prefix, e);
        }
        return repaired;
    }
}
