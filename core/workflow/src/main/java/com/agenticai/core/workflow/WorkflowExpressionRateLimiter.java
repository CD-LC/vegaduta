package com.agenticai.core.workflow;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user limit on the expression evaluate / lint endpoints. These are called from a typing debounce and run
 * tenant-authored expressions against stored data, so they need a ceiling, but one generous enough that a fast
 * typist never meets it: {@code app.workflow.expressions.rate-limit-per-minute} (default 120).
 *
 * <p>Same fixed-window Redis {@code INCR} + {@code EXPIRE} shape as {@code WorkflowTriggerRateLimiter}, including
 * the fallback to an in-process limit when Redis is unreachable (the endpoint degrades to a per-replica limit,
 * never to unlimited).
 */
@Component
public class WorkflowExpressionRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExpressionRateLimiter.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));
    private static final String KEY_PREFIX = "agentic:workflow-expr-rate:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final int maxPerMinute;

    public WorkflowExpressionRateLimiter(
        StringRedisTemplate redisTemplate,
        @Value("${app.workflow.expressions.rate-limit-per-minute:120}") int maxPerMinute
    ) {
        this.redisTemplate = redisTemplate;
        this.maxPerMinute = Math.max(maxPerMinute, 1);
    }

    /** @throws ResponseStatusException 429 when the caller has used up this minute's budget */
    public void check() {
        if (!tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many expression previews - wait a moment and try again.");
        }
    }

    boolean tryAcquire() {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("__no-tenant__");
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse("__no-subject__");
        String key = KEY_PREFIX + tenant + ":" + subject;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Expression rate limiter could not reach Redis for key {} - limiting in-process", key, e);
            return LocalRateLimitFallback.tryAcquire(key, maxPerMinute, WINDOW);
        }
        if (count == null) {
            return LocalRateLimitFallback.tryAcquire(key, maxPerMinute, WINDOW);
        }
        if (count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
        return count <= maxPerMinute;
    }
}
