package com.agenticai.core.tutor;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Limits the tutor's model-backed endpoints (course generation and lesson generation): one fixed
 * hourly window per member, the platform's standard Redis {@code INCR} + conditional {@code EXPIRE}
 * contract (same shape as {@code BriefRateLimiter}). Redis unreachable degrades to the in-process
 * limiter rather than failing open. A hit is a 429 with a plain message.
 *
 * <p>Failed attempts count too - a retry loop against a failing model is exactly what the cap is for.
 * Reading an already-generated lesson is never counted (the generator returns the cache first).
 */
@Component
@ConditionalOnProperty(prefix = "app.tutor", name = "enabled", havingValue = "true")
public class TutorRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TutorRateLimiter.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));
    private static final String KEY_PREFIX = "agentic:tutor-generate:";
    private static final Duration HOUR = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final int maxPerHour;

    public TutorRateLimiter(
        StringRedisTemplate redisTemplate,
        @Value("${app.tutor.generate.max-per-user-per-hour:10}") int maxPerHour
    ) {
        this.redisTemplate = redisTemplate;
        this.maxPerHour = Math.max(1, maxPerHour);
    }

    public int maxPerHour() {
        return maxPerHour;
    }

    /**
     * Counts one generation against {@code subject} in {@code tenantId}.
     *
     * @throws ResponseStatusException 429 once the hourly window is used up.
     */
    public void assertGenerationAllowed(String tenantId, String subject) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        String key = KEY_PREFIX + (tenantId == null ? "-" : tenantId) + ":" + subject;
        if (!hit(key, maxPerHour, HOUR)) {
            log.warn("Tutor generation cap hit for a member of tenant '{}' (cap {} per hour)", tenantId, maxPerHour);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "You have asked the tutor to write " + maxPerHour
                    + " courses or lessons in the last hour. Take a break and try again shortly.");
        }
    }

    /** @return true when the call is within {@code max} for the window. */
    private boolean hit(String key, int max, Duration window) {
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Tutor rate limiter could not reach Redis - limiting in-process", e);
            count = null;
        }
        if (count == null) {
            return LocalRateLimitFallback.tryAcquire(key, max, window);
        }
        if (count == 1L) {
            try {
                redisTemplate.expire(key, window);
            } catch (RuntimeException e) {
                // Without a TTL the key would count forever and lock the member out for good: drop it instead.
                log.warn("Tutor rate limiter could not set the expiry on a window key", e);
                try {
                    redisTemplate.delete(key);
                } catch (RuntimeException ignored) {
                    // nothing more to do; the next call retries the expiry when the count is 1 again
                }
            }
        }
        return count <= max;
    }
}
