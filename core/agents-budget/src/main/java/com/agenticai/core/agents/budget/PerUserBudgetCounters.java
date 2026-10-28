package com.agenticai.core.agents.budget;

import static com.agenticai.core.agents.budget.PerUserBudgetService.MICRO_CENTS_PER_USD;

import com.agenticai.core.agents.budget.PerUserBudgetService.BudgetConfig;
import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * The Redis side of {@link PerUserBudgetService}: key shapes, fail-open counter reads and writes,
 * window TTLs, the exhaustion message and the once-per-window exhaustion bell. Split out of the
 * service to keep it readable; not a bean - one instance is owned by the service. Logs under the
 * service's logger so log routing is unchanged by the split.
 */
final class PerUserBudgetCounters {

    private static final Logger log = LoggerFactory.getLogger(PerUserBudgetService.class);

    private static final String KEY_PREFIX = "agentic:per-user-budget:";
    // P2 (paid top-up / overage) - a SEPARATE per-(tenant, user, modality)
    // counter holding paid top-up credit for the current window (USD
    // micro-cents, same unit as the spend counter). checkBudget adds it to the
    // base cap so a top-up raises the effective ceiling without ever touching
    // the spend counter recordSpend maintains. Deliberately a different key
    // namespace from KEY_PREFIX (note the "-credit" segment) so the two never
    // collide and each expires independently with the window.
    private static final String CREDIT_KEY_PREFIX = "agentic:per-user-budget-credit:";
    /** Blank/absent user identity (some channels have no stable subject) - meter under a per-tenant bucket rather than letting the call escape the gate entirely. */
    private static final String ANONYMOUS_USER_KEY = "_anon";

    private final StringRedisTemplate redisTemplate;
    private final PerUserBudgetTtlRepair ttlRepair;
    // ObjectProvider: the budget-exhaustion bell (Tier-3) is a best-effort
    // side signal - the exception is the enforcement, the notification
    // is just how a human finds out before polling the console.
    private final ObjectProvider<NotificationService> notificationService;

    PerUserBudgetCounters(
        StringRedisTemplate redisTemplate, ObjectProvider<NotificationService> notificationService
    ) {
        this.redisTemplate = redisTemplate;
        this.ttlRepair = new PerUserBudgetTtlRepair(redisTemplate);
        this.notificationService = notificationService;
    }

    /**
     * Notifications (Tier-3): fires the tenant bell once per
     * (tenant,user,modality) budget window when a cap is hit - the audit's
     * "budget alerts require polling the console" gap. The dedupe matters:
     * {@link PerUserBudgetService#checkBudget}/{@code checkAndReserveBudget} throw on EVERY
     * gated call and a retrying user would otherwise mint a notification
     * per retry, so an NX Redis key under the same window TTL does the
     * "once per window" - a Redis outage fails OPEN on the notification
     * side (worst case one extra bell), never on the enforcement side.
     * Recipient is null (broadcast to tenant-admins): {@code userKey} is a
     * billing identity, not guaranteed to be the approver's Keycloak
     * subject, and a mis-targeted silent bell is worse than a shared one.
     */
    void notifyBudgetExhausted(
        String spendKey, long windowSeconds, String tenantId, String userKey, Modality modality, long capMicroCents
    ) {
        try {
            Boolean firstThisWindow = redisTemplate.opsForValue().setIfAbsent(
                spendKey + ":notified", "1",
                java.time.Duration.ofSeconds(Math.max(windowSeconds, 60)));
            if (!Boolean.TRUE.equals(firstThisWindow)) {
                return;
            }
            NotificationService service = notificationService.getIfAvailable();
            if (service == null) {
                return;
            }
            service.create(null, NotificationCategory.BILLING,
                modality.name().toLowerCase(java.util.Locale.ROOT) + " budget exhausted",
                "The per-user " + modality.name().toLowerCase(java.util.Locale.ROOT) + " budget cap ($"
                    + microCentsToUsd(capMicroCents) + ") is exhausted for this window"
                    + " (user '" + userKey + "'). Top up credit or wait for the window to reset.",
                NotificationSeverity.WARNING, null, null);
        } catch (RuntimeException notifyFailure) {
            // Best-effort: a bell-write or Redis failure must never change
            // the budget decision - the exception is the enforcement.
        }
    }

    /**
     * @param windowSeconds the configured window length, used only if this
     *     read discovers the counter has lost its TTL - see {@code
     *     PerUserBudgetTtlRepair#repairMissingTtl}.
     */
    long currentSpendMicroCents(String tenantId, String userKey, Modality modality, int windowSeconds) {
        String spendKey = key(tenantId, userKey, modality);
        try {
            String value = redisTemplate.opsForValue().get(spendKey);
            if (value == null) {
                return 0L;
            }
            ttlRepair.repairMissingTtl(spendKey, windowSeconds);
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            // Corrupt value - fail open (treat as unspent), same posture as a
            // Redis outage: never block a turn on a metering-store hiccup.
            return 0L;
        } catch (RuntimeException e) {
            log.debug("Per-user budget read failed - failing open", e);
            return 0L;
        }
    }

    /**
     * Live paid top-up credit for this window (micro-cents). Fails open to 0
     * on any Redis error or corrupt value, identical posture to {@link
     * #currentSpendMicroCents} - a metering-read hiccup must never manufacture
     * phantom credit nor block a turn.
     *
     * @param windowSeconds see {@link #currentSpendMicroCents}'s parameter of
     *     the same name.
     */
    long currentCreditMicroCents(String tenantId, String userKey, Modality modality, int windowSeconds) {
        String creditKeyStr = creditKey(tenantId, userKey, modality);
        try {
            String value = redisTemplate.opsForValue().get(creditKeyStr);
            if (value == null) {
                return 0L;
            }
            ttlRepair.repairMissingTtl(creditKeyStr, windowSeconds);
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        } catch (RuntimeException e) {
            log.debug("Per-user budget credit read failed - failing open (no credit)", e);
            return 0L;
        }
    }

    /** The keyspace sweep behind {@link PerUserBudgetService#repairAllMissingTtls}. */
    int repairAllPrefixes(int windowSeconds) {
        return ttlRepair.repairPrefix(KEY_PREFIX, windowSeconds)
            + ttlRepair.repairPrefix(CREDIT_KEY_PREFIX, windowSeconds);
    }

    static BigDecimal microCentsToUsd(long microCents) {
        return BigDecimal.valueOf(microCents).divide(MICRO_CENTS_PER_USD, 4, RoundingMode.HALF_UP);
    }

    /** Best-effort TTL (seconds) of a window key, or {@code null} if unknown/absent. */
    Long remainingWindowSeconds(String key) {
        try {
            Long ttl = redisTemplate.getExpire(key);
            return (ttl != null && ttl > 0) ? ttl : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    void incrementBy(String key, Duration window, long by) {
        incrementByAndGet(key, window, by);
    }

    /**
     * Same shape as {@link #incrementBy}, but returns the resulting
     * cumulative value ({@code null} on a Redis outage) so a caller can
     * compare it against a cap atomically - {@link PerUserBudgetService#checkAndReserveBudget}'s
     * reserve-then-compare step needs the post-increment total; {@link
     * #incrementBy}'s existing purely-additive callers don't, so it stays a
     * thin wrapper around this rather than changing its own return type
     * under existing callers.
     */
    Long incrementByAndGet(String key, Duration window, long by) {
        if (by < 0) {
            return refund(key, by);
        }
        try {
            Long count = redisTemplate.opsForValue().increment(key, by);
            if (count != null && count == by) {
                // First write to this window (cumulative now equals exactly
                // what we just added) - set expiry once, same idiom as
                // FreeTierUsageLimiterService#incrementBy.
                redisTemplate.expire(key, window);
            }
            return count;
        } catch (RuntimeException e) {
            // Fail open - a metering write that didn't land just means this
            // call went uncounted, never a broken turn.
            log.debug("Per-user budget write failed - failing open", e);
            return null;
        }
    }

    /**
     * Takes {@code -by} back out of a window that is still open: a rolled-back reservation, or the unused part of one.
     * One atomic script, because a plain {@code INCRBY} of a negative number is wrong in two ways. On a key whose window
     * already expired it creates a fresh negative counter, which is credit the user never paid for: the next window
     * starts below zero and the cap is exceeded by that much. And a refund larger than what is left drives a live
     * counter below zero for the same reason. A refund against an expired window is dropped (that window's spend is
     * gone with its key); one that would overshoot stops at zero. Returns the counter after the refund.
     */
    private Long refund(String key, long by) {
        try {
            return redisTemplate.execute(REFUND_IF_LIVE, java.util.List.of(key), Long.toString(by));
        } catch (RuntimeException e) {
            log.debug("Per-user budget refund failed - failing open", e);
            return null;
        }
    }

    /** KEEPTTL so a clamp never turns a window with an expiry into one without. */
    static final DefaultRedisScript<Long> REFUND_IF_LIVE = new DefaultRedisScript<>(
        "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
            + "local v = redis.call('INCRBY', KEYS[1], ARGV[1]) "
            + "if v < 0 then redis.call('SET', KEYS[1], '0', 'KEEPTTL') v = 0 end "
            + "return v", Long.class);

    String budgetMessage(BudgetConfig config, Modality modality, long capMicroCents, String key) {
        BigDecimal capUsd = BigDecimal.valueOf(capMicroCents).divide(MICRO_CENTS_PER_USD, 4, RoundingMode.HALF_UP);
        String modalityLabel = modality.name().toLowerCase(java.util.Locale.ROOT);
        if (capMicroCents <= 0) {
            return "Your " + modalityLabel + " budget is not included in your plan (pay-per-use only). "
                + "Upgrade or top up to continue.";
        }
        String remaining = "";
        try {
            Long ttl = redisTemplate.getExpire(key);
            if (ttl != null && ttl > 0) {
                remaining = " Your budget resets in about " + humanizeSeconds(ttl) + ".";
            }
        } catch (RuntimeException ignored) {
            // best-effort only
        }
        return "Daily " + modalityLabel + " budget reached ($" + capUsd.toPlainString() + " per user)."
            + remaining + " Top up or upgrade to continue.";
    }

    private static String humanizeSeconds(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return seconds + "s";
    }

    static String key(String tenantId, String userKey, Modality modality) {
        String subject = (userKey == null || userKey.isBlank()) ? ANONYMOUS_USER_KEY : userKey;
        return KEY_PREFIX + modality.name().toLowerCase(java.util.Locale.ROOT) + ":" + tenantId + ":" + subject;
    }

    /** Companion of {@link #key} for the P2 paid top-up credit counter - same (tenant, user, modality) shape, distinct namespace. */
    static String creditKey(String tenantId, String userKey, Modality modality) {
        String subject = (userKey == null || userKey.isBlank()) ? ANONYMOUS_USER_KEY : userKey;
        return CREDIT_KEY_PREFIX + modality.name().toLowerCase(java.util.Locale.ROOT) + ":" + tenantId + ":" + subject;
    }
}
