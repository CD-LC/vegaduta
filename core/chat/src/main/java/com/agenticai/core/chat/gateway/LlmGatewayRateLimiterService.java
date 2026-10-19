package com.agenticai.core.chat.gateway;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * #848 (LLM gateway seam) - per-tenant fixed-window rate limiter, the "screen before it reaches the
 * LLM" half of the gateway the companion research doc ({@code
 * Agentic-AI-Platform-Gateway-MultiAgent-OCR-IVR-Research.md}, section 2) called out as missing:
 * #829's provider fallback only reacts AFTER a call already went out, this rejects before one does.
 *
 * <p><b>Master Roadmap 2026 Section2.1:</b> previously in-memory, per-instance state (a plain
 * {@code ConcurrentHashMap}) - correct on a single instance, but on a multi-replica deployment each
 * instance enforced its own independent window, so the EFFECTIVE tenant-wide limit was {@code
 * maxCallsPerMinute * replicaCount}, not a hard global ceiling; documented at the time as a known
 * limitation blocking horizontal scaling, not an oversight. Now Redis-backed - plain {@code INCR} +
 * conditional {@code EXPIRE}, fail-open if Redis is unreachable (an abuse throttle degrading open
 * is the right trade-off; it must never be the reason a legitimate LLM call gets rejected) - the
 * exact same shape {@link com.agenticai.core.agents.tools.ToolCircuitBreakerService} and {@link
 * com.agenticai.core.omnichannel.trigger.WorkflowTriggerRateLimiter} already use elsewhere in this
 * codebase. {@code core.chat} previously had no path to Redis (neither of its two module
 * dependencies, {@code providers}/{@code security}, bring in {@code spring-boot-starter-data-redis}
 * transitively - confirmed by reading both poms directly); this module's own {@code pom.xml} now
 * declares it directly, same as {@code core.agents}/{@code core.tenancy} already do.
 *
 * <p><b>Infra audit (2026-07-27) - plan-scoped ceiling.</b> This was a single flat {@code
 * maxCallsPerWindow} applied identically to every tenant regardless of {@link TenantPlan}, despite
 * the platform's FREE/PREMIUM/ UNLIMITED tiering being intended to scale every other governance
 * seam ({@code FreeTierUsageLimiterService}, {@code LlmGatewayBudgetService}, {@code
 * PerUserBudgetService}). Now resolves the tenant's plan via {@link TenantLimits#plan} - the same
 * read-only accessor {@code LlmGatewayBudgetService} already injects in this exact package - and
 * picks one of three config-driven ceilings. A plan-lookup failure fails OPEN (skips the whole
 * check, same posture as a Redis outage below) rather than defaulting to the most restrictive tier
 * - a governance-lookup hiccup must never be the reason a legitimate call is rejected.
 */
@Component
public class LlmGatewayRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayRateLimiterService.class);

    /**
     * One WARN per minute per call site while a dependency is down, instead of one (with a stack
     * trace) per request.
     */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    private static final String KEY_PREFIX = "agentic:llm-gateway-rate:";
    // Infra audit (2026-07-27) - same shared metric name as
    // LlmGatewayBudgetService.REJECTIONS_METRIC (see that field's javadoc);
    // duplicated as a literal rather than a cross-class reference to avoid a
    // pointless compile-time coupling between two otherwise-independent
    // gateway checks over one shared string constant.
    private static final String REJECTIONS_METRIC = "llm.gateway.rejections";
    private static final String RATE_LIMIT_EXCEEDED_REASON = "rate_limit_exceeded";
    // Component tag on GovernanceDegradationSignal.METRIC_NAME - one name for
    // every fail-open branch in this class, so the dashboard sees "the LLM
    // gateway rate limiter is degraded" as a single series.
    private static final String DEGRADATION_COMPONENT = "llm-gateway-rate-limiter";

    private final StringRedisTemplate redisTemplate;
    private final TenantLimits tenantLimits;
    private final long freeMaxCallsPerMinute;
    private final long premiumMaxCallsPerMinute;
    private final long unlimitedMaxCallsPerMinute;
    private final Duration window;
    private final MeterRegistry meterRegistry;
    private final GovernanceDegradationSignal degradationSignal;

    public LlmGatewayRateLimiterService(
            StringRedisTemplate redisTemplate,
            TenantLimits tenantLimits,
            @Value("${app.llm.gateway.rate-limit.free-max-calls-per-minute:30}")
                    long freeMaxCallsPerMinute,
            @Value("${app.llm.gateway.rate-limit.premium-max-calls-per-minute:150}")
                    long premiumMaxCallsPerMinute,
            @Value("${app.llm.gateway.rate-limit.unlimited-max-calls-per-minute:600}")
                    long unlimitedMaxCallsPerMinute,
            MeterRegistry meterRegistry,
            GovernanceDegradationSignal degradationSignal) {
        this.redisTemplate = redisTemplate;
        this.tenantLimits = tenantLimits;
        this.freeMaxCallsPerMinute = freeMaxCallsPerMinute;
        this.premiumMaxCallsPerMinute = premiumMaxCallsPerMinute;
        this.unlimitedMaxCallsPerMinute = unlimitedMaxCallsPerMinute;
        this.window = Duration.ofMinutes(1);
        this.meterRegistry = meterRegistry;
        this.degradationSignal = degradationSignal;
    }

    /**
     * Plan-scoped ceiling - FREE baseline, PREMIUM mid, UNLIMITED high (effectively no practical
     * ceiling).
     */
    private long maxCallsFor(TenantPlan plan) {
        return switch (plan) {
            case UNLIMITED -> unlimitedMaxCallsPerMinute;
            case PREMIUM -> premiumMaxCallsPerMinute;
            case FREE -> freeMaxCallsPerMinute;
        };
    }

    /**
     * @throws LlmGatewayLimitExceededException if this tenant has already made {@link #maxCallsFor}
     *     (this tenant's plan-scoped ceiling) gateway calls within the current fixed window (reset
     *     on the first call after expiry - not a sliding window, same simplification the sibling
     *     Redis-backed limiters in this codebase make). {@code tenantId} null (no tenant context -
     *     an internal/system call) skips the check entirely rather than sharing one global counter
     *     across every tenant-less caller.
     */
    public void assertWithinRateLimit(String tenantId) {
        assertWithinRateLimit(tenantId, null);
    }

    /**
     * As {@link #assertWithinRateLimit(String)} for a caller that already resolved the tenant's
     * effective plan for this turn: {@code knownPlan} is used as is, so the check costs no plan
     * lookup of its own. {@code null} reads the plan.
     */
    public void assertWithinRateLimit(String tenantId, TenantPlan knownPlan) {
        if (tenantId == null) {
            return;
        }
        long maxCallsPerWindow;
        try {
            maxCallsPerWindow =
                    maxCallsFor(knownPlan != null ? knownPlan : tenantLimits.plan(tenantId));
        } catch (RuntimeException e) {
            // Plan lookup failed - fail open (skip the check entirely), same
            // posture as a Redis outage below: a governance-lookup hiccup
            // must never be the reason a legitimate call is rejected.
            log.warn(
                    "LLM gateway rate limiter could not resolve plan for tenant {} - failing open",
                    tenantId,
                    e);
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
            return;
        }
        String key = KEY_PREFIX + tenantId;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            // Redis unreachable (connection failure, timeout, etc.) - fail
            // open, see class javadoc. An LLM gateway hiccup that also takes
            // down this throttle must never compound into rejecting every
            // legitimate call on top of it.
            REDIS_DOWN_LOG.warnf(
                    "LLM gateway rate limiter could not reach Redis for tenant {} - limiting"
                            + " in-process",
                    tenantId,
                    e);
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
            count = -1L;
        }
        if (count == null || count < 0) {
            if (count == null) {
                degradationSignal.failOpen(DEGRADATION_COMPONENT, null);
            }
            // Redis unreachable. Security audit 2026-09-18: this used to skip
            // the check, which made a Redis outage the moment every tenant's
            // LLM spend ceiling disappeared. The same per-plan ceiling is now
            // applied in-process until Redis is back - see
            // LocalRateLimitFallback. (A reachable-but-odd Redis returning null
            // lands here too.)
            if (!LocalRateLimitFallback.tryAcquire(key, maxCallsPerWindow, window)) {
                meterRegistry
                        .counter(
                                REJECTIONS_METRIC,
                                "tenant",
                                tenantId,
                                "reason",
                                RATE_LIMIT_EXCEEDED_REASON)
                        .increment();
                throw new LlmGatewayLimitExceededException(
                        "This tenant has exceeded "
                                + maxCallsPerWindow
                                + " LLM calls/minute - please slow down and retry shortly.");
            }
            return;
        }
        if (count == 1L) {
            redisTemplate.expire(key, window);
        }
        if (count > maxCallsPerWindow) {
            meterRegistry
                    .counter(
                            REJECTIONS_METRIC,
                            "tenant",
                            tenantId,
                            "reason",
                            RATE_LIMIT_EXCEEDED_REASON)
                    .increment();
            throw new LlmGatewayLimitExceededException(
                    "This tenant has exceeded "
                            + maxCallsPerWindow
                            + " LLM calls/minute - please slow down and retry shortly.");
        }
    }
}
