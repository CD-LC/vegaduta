package com.agenticai.core.agents.tools;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * #815 Governance follow-up (Fail-safe: circuit breakers on repeated failures) - distinct from the
 * existing per-call retry-with-backoff ({@code RetryFailedActionsJob}, which retries a single
 * failed action once): this trips per {@code (tenantId, toolName)} after {@link #failureThreshold}
 * consecutive failures within a rolling {@link #window}, and short-circuits every further call to
 * that specific tool for {@link #cooldown}, instead of letting an agent hammer a broken dependency
 * turn after turn (or run after run, since this is keyed by tool name, not by session).
 *
 * <p>Same shape as {@link com.agenticai.core.omnichannel.trigger.WorkflowTriggerRateLimiter} and
 * {@code SignupRateLimiter} - plain Redis commands, fail-open if Redis is unreachable (this is an
 * availability safeguard, not a correctness guarantee; degrading open on an infra hiccup is
 * strictly better than blocking every tool call platform-wide because Redis briefly hiccupped).
 *
 * <p>Three keys per {@code (tenant, tool)} pair:
 *
 * <ul>
 *   <li>{@code failures:...} - a plain counter, {@code INCR}'d on every failure and {@code DEL}'d
 *       on the next success, so it only ever tracks a CONSECUTIVE failure streak, not
 *       failures-per-window in general. Expires after {@link #window} so a slow trickle of isolated
 *       failures spread far apart never accumulates toward the threshold.
 *   <li>{@code open:...} - presence alone means "circuit open"; set with a {@link #cooldown} TTL
 *       the moment the failure counter reaches {@link #failureThreshold}, and the failure counter
 *       is reset at the same time so the next attempt after cooldown starts a fresh streak.
 *   <li>{@code lasterror:...} - the redacted detail of the failure that tripped the breaker, set
 *       alongside {@code open:...} with the same cooldown TTL, so both the model-facing
 *       "temporarily disabled" message and the admin circuit-breaker listing can name the actual
 *       cause instead of reporting an unexplained outage.
 * </ul>
 */
@Component
public class ToolCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(ToolCircuitBreakerService.class);

    /**
     * One WARN per minute per call site while a dependency is down, instead of one (with a stack
     * trace) per request.
     */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    private static final String FAILURE_KEY_PREFIX = "agentic:tool-circuit:failures:";
    private static final String OPEN_KEY_PREFIX = "agentic:tool-circuit:open:";
    private static final String LAST_ERROR_KEY_PREFIX = "agentic:tool-circuit:lasterror:";
    private static final String NO_TENANT = "__no-tenant__";

    /** Cap on the stored last-error detail - diagnostic context, not a stack dump. */
    private static final int MAX_LAST_ERROR_CHARS = 400;

    // Component tag on GovernanceDegradationSignal.METRIC_NAME - all three
    // fail-open branches in this class report as one series.
    private static final String DEGRADATION_COMPONENT = "tool-circuit-breaker";

    private final StringRedisTemplate redisTemplate;
    private final GovernanceDegradationSignal degradationSignal;
    private final long failureThreshold;
    private final Duration window;
    private final Duration cooldown;

    public ToolCircuitBreakerService(
            StringRedisTemplate redisTemplate,
            GovernanceDegradationSignal degradationSignal,
            @Value("${app.tool-circuit-breaker.failure-threshold:5}") long failureThreshold,
            @Value("${app.tool-circuit-breaker.window-minutes:5}") long windowMinutes,
            @Value("${app.tool-circuit-breaker.cooldown-minutes:10}") long cooldownMinutes) {
        this.redisTemplate = redisTemplate;
        this.degradationSignal = degradationSignal;
        this.failureThreshold = failureThreshold;
        this.window = Duration.ofMinutes(windowMinutes);
        this.cooldown = Duration.ofMinutes(cooldownMinutes);
    }

    /**
     * @return true if this tool is currently short-circuited for the calling tenant and must not be
     *     invoked - the caller should return an explanatory result to the model instead of calling
     *     the delegate, same "soft stop, not a hard failure" posture {@link
     *     com.agenticai.core.guardrails.GuardedToolCallback} already uses for approval-gating and
     *     verification rejections. Fails open (returns {@code false}) on any Redis error.
     */
    public boolean isOpen(String toolName) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(openKey(toolName)));
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "Circuit-breaker check failed for tool '{}' - Redis unreachable? Failing open (treating as not-open).",
                    toolName,
                    e);
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
            return false;
        }
    }

    /**
     * Remaining cooldown seconds and the redacted detail of the tripping failure, or {@code null}
     * when the circuit is closed. Lets the caller build a "disabled until ~N min, last error: X"
     * explanation in one check. Fails open (returns {@code null}) on any Redis error - same
     * contract as {@link #isOpen}.
     */
    public OpenInfo openInfo(String toolName) {
        try {
            Long ttl = redisTemplate.getExpire(openKey(toolName));
            if (ttl == null || ttl < 0) {
                return null;
            }
            return new OpenInfo(ttl, redisTemplate.opsForValue().get(lastErrorKey(toolName)));
        } catch (RuntimeException e) {
            log.warn(
                    "Circuit-breaker check failed for tool '{}' - Redis unreachable? Failing open (treating as not-open).",
                    toolName,
                    e);
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
            return null;
        }
    }

    /**
     * @param secondsRemaining cooldown TTL left; @param lastError redacted tripping failure detail,
     *     possibly null.
     */
    public record OpenInfo(long secondsRemaining, String lastError) {}

    /** Resets the consecutive-failure streak. Best-effort - never throws. */
    public void recordSuccess(String toolName) {
        try {
            redisTemplate.delete(failureKey(toolName));
        } catch (RuntimeException e) {
            // Fail open - a missed reset just means the next failure counts
            // one higher than it should, never a functional problem. Still a
            // degradation signal: if Redis is down, isOpen/recordFailure are
            // degraded too.
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
        }
    }

    /**
     * Increments the consecutive-failure streak and trips the circuit if it just reached {@link
     * #failureThreshold}. Best-effort - never throws.
     */
    public void recordFailure(String toolName) {
        recordFailure(toolName, null);
    }

    /**
     * Same as {@link #recordFailure(String)} but also stores {@code lastError} (already-redacted,
     * truncated) alongside the trip marker so the open state carries its own explanation - surfaced
     * by {@link #openInfo} and the admin circuit-breaker listing. Only the error recorded on the
     * trip itself is kept; earlier streak failures are not retained.
     */
    public void recordFailure(String toolName, String lastError) {
        try {
            String key = failureKey(toolName);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return;
            }
            if (count == 1L) {
                redisTemplate.expire(key, window);
            }
            if (count >= failureThreshold) {
                redisTemplate.opsForValue().set(openKey(toolName), "1", cooldown);
                if (lastError != null && !lastError.isBlank()) {
                    String detail =
                            lastError.length() > MAX_LAST_ERROR_CHARS
                                    ? lastError.substring(0, MAX_LAST_ERROR_CHARS) + "..."
                                    : lastError;
                    redisTemplate.opsForValue().set(lastErrorKey(toolName), detail, cooldown);
                }
                redisTemplate.delete(key);
            }
        } catch (RuntimeException e) {
            // Fail open - see class javadoc.
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
        }
    }

    private static String failureKey(String toolName) {
        return FAILURE_KEY_PREFIX + tenant() + ":" + toolName;
    }

    private static String openKey(String toolName) {
        return OPEN_KEY_PREFIX + tenant() + ":" + toolName;
    }

    private static String lastErrorKey(String toolName) {
        return LAST_ERROR_KEY_PREFIX + tenant() + ":" + toolName;
    }

    private static String tenant() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(NO_TENANT);
    }
}
