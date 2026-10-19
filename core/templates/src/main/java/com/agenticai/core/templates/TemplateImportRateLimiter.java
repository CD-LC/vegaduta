package com.agenticai.core.templates;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-user-per-day cap on template imports - the abuse control this module's
 * README previously flagged as "flagged, not implemented" (imports leave no
 * {@code community_template} row to count against cheaply, so the
 * DB-count approach {@code enforceSubmissionCap} uses for drafts doesn't
 * transfer). A Redis counter does the metering instead: the platform's
 * established fixed-window limiter contract - plain {@code INCR} +
 * conditional {@code EXPIRE}, <b>fail-open on any Redis error</b> - the
 * exact same shape {@code SignupRateLimiter}, {@code
 * LlmGatewayRateLimiterService} and {@code AgentShareService}'s {@code
 * tryAcquire*} family already use; deliberately NOT a new algorithm or a
 * rate-limiting library. Redis-backed means the cap holds across multiple
 * {@code core} replicas.
 *
 * <p>Keyed by the caller's Keycloak subject (imports are per-USER, unlike
 * the per-TENANT draft-creation cap), over a rolling 24h window anchored at
 * the first import - same window shape as the submission cap. A hit throws
 * {@link TemplateImportLimitException} (429) and is logged, never silently
 * dropped, per the contract's abuse-limits posture.
 */
@Component
public class TemplateImportRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TemplateImportRateLimiter.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));
    private static final String KEY_PREFIX = "agentic:template-import-rate:";
    private static final Duration WINDOW = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;
    private final int maxImportsPerUserPerDay;

    public TemplateImportRateLimiter(
        StringRedisTemplate redisTemplate,
        @Value("${app.templates.max-imports-per-user-per-day:20}") int maxImportsPerUserPerDay
    ) {
        this.redisTemplate = redisTemplate;
        this.maxImportsPerUserPerDay = maxImportsPerUserPerDay;
    }

    /**
     * Count one import attempt against {@code subject}'s daily window.
     * Failed attempts (e.g. a 404 on the template id) count too - that also
     * lids template-id enumeration, and a legitimate user retrying a handful
     * of failures stays nowhere near the cap.
     *
     * @throws TemplateImportLimitException (429) once the caller exceeds
     *     the configured daily cap.
     */
    public void assertWithinDailyCap(String subject) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        String key = KEY_PREFIX + subject;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            // Redis unreachable - degrade to the in-process limiter rather than
            // failing open (security audit 2026-09-18, see LocalRateLimitFallback).
            REDIS_DOWN_LOG.warnf("Template import rate limiter could not reach Redis for {} - limiting in-process", subject, e);
            count = null;
        }
        if (count == null) {
            if (!LocalRateLimitFallback.tryAcquire(key, maxImportsPerUserPerDay, WINDOW)) {
                throw new TemplateImportLimitException(maxImportsPerUserPerDay);
            }
            return;
        }
        if (count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
        if (count > maxImportsPerUserPerDay) {
            log.warn("Template import cap hit for user {} ({} in current 24h window, cap {})",
                subject, count, maxImportsPerUserPerDay);
            throw new TemplateImportLimitException(maxImportsPerUserPerDay);
        }
    }
}
