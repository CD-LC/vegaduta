package com.agenticai.core.workflow;

import com.agenticai.core.resilience.RateLimitedLog;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window (one minute) per-key limiter for the PUBLIC workflow endpoints - the hosted form and the Wait
 * resume URL. Same Redis {@code INCR} + first-hit {@code EXPIRE}, fail-open shape as the workflow-share limiter
 * ({@code WorkflowShareService#tryAcquire}): abuse control, never a reason an outage of Redis should take the
 * endpoint down.
 */
@Component
public class WorkflowPublicRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPublicRateLimiter.class);
    /** One WARN per minute per call site while Redis is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));

    private final StringRedisTemplate redisTemplate;

    public WorkflowPublicRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @param keyPrefix scope of the counter, e.g. {@code agentic:workflow-form-token:}
     * @param key       the counted identity (a token hash, an IP hash) - a blank key is never limited
     * @return true while the caller is within {@code limitPerMinute}
     */
    public boolean tryAcquire(String keyPrefix, String key, long limitPerMinute) {
        if (key == null || key.isBlank() || limitPerMinute <= 0) {
            return true;
        }
        String redisKey = keyPrefix + key;
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                redisTemplate.expire(redisKey, Duration.ofMinutes(1));
            }
            return count <= limitPerMinute;
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Workflow public rate limiter ({}) could not reach Redis - failing open", keyPrefix, e);
            return true;
        }
    }
}
