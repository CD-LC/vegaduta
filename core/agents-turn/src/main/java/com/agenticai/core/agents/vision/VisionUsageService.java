package com.agenticai.core.agents.vision;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantPlan;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-USER-per-day allowances for server-side
 * vision: how many camera/screen frames a user may have analyzed, and how
 * many continuous WATCH/LIVE minutes they may consume. Same Redis {@code
 * INCR} + conditional {@code EXPIRE} fixed-window pattern as {@link
 * com.agenticai.core.agents.quota.FreeTierUsageLimiterService} (see that class's
 * javadoc for the known "not perfectly atomic, acceptable for a
 * throttle-shaped check" limitation and the fail-open-on-Redis-outage
 * posture, both of which apply here identically).
 *
 * <p><b>Outage posture (deliberate, 2026-10-04 review):</b> with Redis down the daily frame and
 * watch-minute caps are NOT enforced (no in-process fallback counter), exactly like the sibling
 * per-day quota counters {@code FreeTierUsageLimiterService} and {@code PerUserBudgetService}. The
 * per-minute abuse limiters use {@code LocalRateLimitFallback} instead because a rate limit is
 * protection that must keep working; a daily allowance is a soft cost cap, and a per-replica local
 * counter that resets on every deploy would not be a cap either. Recorded in
 * CORE-RESILIENCE-AUDIT.md section 5.
 *
 * <p>Keyed per (tenant, user) rather than per tenant - vision frames are the
 * platform's own pool spend on FREE (and camera frames are privacy-sensitive
 * enough that one user must never be able to exhaust a teammate's
 * allowance), same per-user granularity {@code PerUserBudgetService} already
 * uses for dollar budgets. The plan selects which cap applies, resolved by
 * the caller the same way {@code FreeTierUsageLimiterService}'s call sites
 * do ({@code TenantLimits#plan}): FREE gets {@code app.vision.frames.free-daily},
 * every paid plan {@code app.vision.frames.premium-daily} / {@code
 * app.vision.watch.minutes-premium-daily} - all live config, see {@link
 * VisionProperties}.
 */
@Component
public class VisionUsageService {

    private static final Logger log = LoggerFactory.getLogger(VisionUsageService.class);
    /** One WARN per minute while Redis is down, not one per vision frame. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));

    private static final String FRAMES_KEY_PREFIX = "agentic:vision-frames:daily:";
    private static final String WATCH_MINUTES_KEY_PREFIX = "agentic:vision-watch-minutes:daily:";
    // Same one-metric-name-many-reason-tags convention as
    // FreeTierUsageLimiterService's llm.gateway.rejections counter, under a
    // vision-scoped name (these rejections never reach the LLM gateway).
    private static final String REJECTIONS_METRIC = "vision.rejections";

    private final StringRedisTemplate redisTemplate;
    private final VisionProperties properties;
    private final MeterRegistry meterRegistry;

    public VisionUsageService(
        StringRedisTemplate redisTemplate,
        VisionProperties properties,
        MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** The plan-scoped daily frame cap - FREE 1x its own number, every paid plan the premium number. */
    public int framesDailyCapFor(TenantPlan plan) {
        return plan == TenantPlan.FREE
            ? properties.getFrames().getFreeDaily()
            : properties.getFrames().getPremiumDaily();
    }

    /**
     * The plan-scoped daily WATCH/LIVE minute cap. 0 for FREE - continuous
     * modes are additionally plan-gated off for FREE via {@code
     * plan_limits.live_vision_allowed}, so this is belt-and-braces, not the
     * primary gate.
     */
    public int watchMinutesDailyCapFor(TenantPlan plan) {
        return plan == TenantPlan.FREE ? 0 : properties.getWatch().getMinutesPremiumDaily();
    }

    /**
     * Consumes {@code frames} camera/screen frames against the user's daily
     * window, or throws {@link VisionQuotaExceededException} (HTTP 429) if
     * that would exceed the plan's cap. Call BEFORE the model is invoked -
     * same "a rejected turn must cost nothing further" placement discipline
     * as {@code FreeTierUsageLimiterService#checkAndReserveTokenBudget}. Fail-open on a
     * Redis outage (increment returns null): vision metering is a spend
     * throttle, not billing correctness - identical posture, and reasoning,
     * to that class's own fail-open note.
     */
    public void recordFrames(String tenantId, String userSubject, TenantPlan plan, int frames) {
        if (frames <= 0) {
            return;
        }
        int cap = framesDailyCapFor(plan);
        long count = incrementBy(FRAMES_KEY_PREFIX + tenantId + ":" + userSubject, frames);
        if (count > cap) {
            meterRegistry.counter(REJECTIONS_METRIC, "tenant", tenantId, "reason", "vision_daily_frame_limit").increment();
            throw new VisionQuotaExceededException(
                "Daily vision frame limit reached (" + cap + " frames/day on your plan). "
                    + (plan == TenantPlan.FREE
                        ? "Upgrade for a much higher limit, or keep using on-device analysis - it doesn't count against this."
                        : "Try again tomorrow, or contact support to raise this limit."));
        }
    }

    /**
     * How many frames this user may still send today - what {@code POST
     * /api/vision/sessions} returns as {@code frameAllowanceRemaining}, and
     * what {@link VisionSessionService#start} checks for headroom. Read-only
     * (a user opening the camera panel must not consume quota); never
     * negative.
     */
    public long framesRemaining(String tenantId, String userSubject, TenantPlan plan) {
        long used = currentCount(FRAMES_KEY_PREFIX + tenantId + ":" + userSubject);
        return Math.max(0, framesDailyCapFor(plan) - used);
    }

    /**
     * Pre-start headroom check for a continuous WATCH/LIVE session - throws
     * {@link VisionQuotaExceededException} (429) when the user's daily
     * minute window is already exhausted. Read-only, the pre-call counterpart
     * to {@code FreeTierUsageLimiterService#checkAndReserveTokenBudget}: the
     * session's own minutes aren't known yet ({@link #recordWatchMinutes}
     * adds them when it ends).
     */
    public void checkWatchMinutesHeadroom(String tenantId, String userSubject, TenantPlan plan) {
        int cap = watchMinutesDailyCapFor(plan);
        long used = currentCount(WATCH_MINUTES_KEY_PREFIX + tenantId + ":" + userSubject);
        if (used >= cap) {
            meterRegistry.counter(REJECTIONS_METRIC, "tenant", tenantId, "reason", "vision_daily_watch_minute_limit").increment();
            throw new VisionQuotaExceededException(
                "Daily watch-session limit reached (" + cap + " minutes/day on your plan). Try again tomorrow.");
        }
    }

    /**
     * Post-session minute accounting - called when a WATCH/LIVE session ends
     * ({@link VisionSessionService#end}), adding its wall-clock minutes to
     * the window. Deliberately never throws: the minutes are already spent by
     * the time this runs; {@link #checkWatchMinutesHeadroom} is what blocks
     * the NEXT session once this pushes a user over. Same shape as {@code
     * FreeTierUsageLimiterService#recordTokenUsage}.
     */
    public void recordWatchMinutes(String tenantId, String userSubject, long minutes) {
        if (minutes <= 0) {
            return;
        }
        incrementBy(WATCH_MINUTES_KEY_PREFIX + tenantId + ":" + userSubject, minutes);
    }

    private long currentCount(String key) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            // Redis unreachable. This class's javadoc promises fail-OPEN on an outage, but only the
            // null-return case was handled: a real connection failure threw out of the vision turn
            // as a 500. Bug fix (no flag): degrade to "nothing used yet" and say so once a minute.
            REDIS_DOWN_LOG.warn("vision-usage", "Vision usage counters unavailable (Redis error: "
                + e.getClass().getSimpleName() + ") - failing open until it recovers");
            return 0;
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // Corrupt/unexpected value - fail open, same posture as
            // FreeTierUsageLimiterService#currentCount.
            return 0;
        }
    }

    private long incrementBy(String key, long by) {
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key, by);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warn("vision-usage", "Vision usage counters unavailable (Redis error: "
                + e.getClass().getSimpleName() + ") - failing open until it recovers");
            return 0;
        }
        if (count == null) {
            // Redis unreachable - fail open (see recordFrames javadoc).
            return 0;
        }
        if (count == by) {
            // First write to this window - set the expiry only on the write
            // that created the key, same pattern as
            // FreeTierUsageLimiterService#incrementBy.
            try {
                redisTemplate.expire(key, Duration.ofDays(1));
            } catch (RuntimeException e) {
                // The increment landed; a lost EXPIRE only means the key outlives its day (the same
                // known gap the class javadoc accepts), never a failed turn.
                REDIS_DOWN_LOG.warn("vision-usage", "Vision usage counter EXPIRE failed (Redis error: "
                    + e.getClass().getSimpleName() + ")");
            }
        }
        return count;
    }
}
