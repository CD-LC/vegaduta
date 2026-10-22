package com.agenticai.core.brief;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.logging.LogSafe;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Limits {@code POST /api/briefs/plan} - each call runs a skeleton plus several per-artifact LLM
 * calls, so it is the expensive endpoint of the feature. Two fixed windows per caller, both the
 * platform's standard Redis {@code INCR} + conditional {@code EXPIRE} contract (same shape as
 * {@link com.agenticai.core.security.ratelimit.LocalRateLimitFallback}'s siblings and {@code
 * TemplateImportRateLimiter}): a burst window (default 5 per minute, all plans) and a daily cap
 * that depends on the tenant's {@link TenantPlan}. Redis unreachable degrades to the in-process
 * limiter rather than failing open. A hit throws {@link BriefRateLimitException} (429).
 *
 * <p>The per-plan defaults are configuration, not policy statements: override with {@code
 * app.briefs.plan-calls-per-day.free|premium|unlimited} and {@code
 * app.briefs.plan-burst-per-minute}.
 */
@Component
public class BriefRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(BriefRateLimiter.class);

    /**
     * One WARN per minute per call site while a dependency is down, instead of one (with a stack
     * trace) per request.
     */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    private static final String KEY_PREFIX = "agentic:brief-plan-rate:";
    private static final Duration DAY = Duration.ofDays(1);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final int burstPerMinute;
    private final int freePerDay;
    private final int premiumPerDay;
    private final int unlimitedPerDay;

    public BriefRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.briefs.plan-burst-per-minute:5}") int burstPerMinute,
            @Value("${app.briefs.plan-calls-per-day.free:5}") int freePerDay,
            @Value("${app.briefs.plan-calls-per-day.premium:50}") int premiumPerDay,
            @Value("${app.briefs.plan-calls-per-day.unlimited:500}") int unlimitedPerDay) {
        this.redisTemplate = redisTemplate;
        this.burstPerMinute = burstPerMinute;
        this.freePerDay = freePerDay;
        this.premiumPerDay = premiumPerDay;
        this.unlimitedPerDay = unlimitedPerDay;
    }

    /**
     * Daily cap for a plan (also lets the UI say "N left today" later without duplicating the
     * numbers).
     */
    public int dailyCapFor(TenantPlan plan) {
        return switch (plan == null ? TenantPlan.FREE : plan) {
            case FREE -> freePerDay;
            case PREMIUM -> premiumPerDay;
            case UNLIMITED -> unlimitedPerDay;
        };
    }

    /**
     * Count one plan call against {@code subject}'s burst and daily windows. Failed attempts count
     * too (a retry loop against a failing model is exactly what the cap is for).
     *
     * @throws BriefRateLimitException once either window is exceeded.
     */
    public void assertPlanCallAllowed(String subject, TenantPlan plan) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        if (!hit(KEY_PREFIX + "min:" + subject, burstPerMinute, MINUTE)) {
            log.warn(
                    "Brief plan burst cap hit for {} (cap {} per minute)",
                    LogSafe.clean(subject),
                    LogSafe.clean(burstPerMinute));
            throw new BriefRateLimitException(
                    "You are planning too quickly. Wait a minute, then try again (limit: "
                            + burstPerMinute
                            + " per minute).");
        }
        int daily = dailyCapFor(plan);
        if (!hit(KEY_PREFIX + "day:" + subject, daily, DAY)) {
            log.warn(
                    "Brief plan daily cap hit for {} (cap {} per day, plan {})",
                    LogSafe.clean(subject),
                    LogSafe.clean(daily),
                    LogSafe.clean(plan));
            throw new BriefRateLimitException(
                    "You have used today's "
                            + daily
                            + " brief plans for your plan. Try again tomorrow"
                            + (plan == TenantPlan.UNLIMITED
                                    ? "."
                                    : ", or move to a higher plan for more."));
        }
    }

    /**
     * Rehearsals and experiments per tenant per hour: each one spends the tenant's own model
     * budget.
     */
    static final int PROOF_RUNS_PER_TENANT_HOUR = 12;

    /**
     * Counts one budget-spending proof action (a rehearsal or an experiment) against the whole
     * tenant, so several members cannot multiply the cap the way a per-subject window would.
     */
    public void assertProofRunAllowed(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        if (!hit(
                KEY_PREFIX + "proof:" + tenantId,
                PROOF_RUNS_PER_TENANT_HOUR,
                Duration.ofHours(1))) {
            log.warn(
                    "Brief proof-run cap hit for tenant {} (cap {} per hour)",
                    tenantId,
                    PROOF_RUNS_PER_TENANT_HOUR);
            throw new BriefRateLimitException(
                    "Your workspace has started "
                            + PROOF_RUNS_PER_TENANT_HOUR
                            + " rehearsals and experiments in the last hour. Try again shortly.");
        }
    }

    /**
     * @return true when the call is within {@code max} for the window.
     */
    private boolean hit(String key, int max, Duration window) {
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "Brief rate limiter could not reach Redis for {} - limiting in-process",
                    key,
                    e);
            count = null;
        }
        if (count == null) {
            return LocalRateLimitFallback.tryAcquire(key, max, window);
        }
        if (count == 1L) {
            try {
                redisTemplate.expire(key, window);
            } catch (RuntimeException e) {
                // Without a TTL the key would count forever and lock the caller out for good: drop
                // it instead.
                log.warn(
                        "Brief rate limiter could not set the expiry on {}", LogSafe.clean(key), e);
                try {
                    redisTemplate.delete(key);
                } catch (RuntimeException ignored) {
                    // nothing more to do; the next call retries the expiry when the count is 1
                    // again
                }
            }
        }
        return count <= max;
    }
}
