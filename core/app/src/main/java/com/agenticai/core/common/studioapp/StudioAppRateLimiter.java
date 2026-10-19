package com.agenticai.core.common.studioapp;

import com.agenticai.core.security.ratelimit.RedisFixedWindowCounter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-IP fixed-window limits on the anonymous {@code /api/public/apps/**}
 * surface, via {@link RedisFixedWindowCounter} (atomic window; in-process
 * fallback rather than fail-open while Redis is down - the auth bucket is the
 * login brute-force throttle and must not vanish with Redis). These are abuse lids
 * on top of - never instead of - the owning tenant's plan/budget metering,
 * which the underlying agent/workflow/hosted-pool calls already apply.
 */
@Service
public class StudioAppRateLimiter {

    private static final String PREFIX = "agentic:studio-app-rate:";

    private final StringRedisTemplate redisTemplate;
    private final long maxCallsPerMinute;
    private final long maxAiPerMinute;
    private final long maxAuthAttemptsPerMinute;

    public StudioAppRateLimiter(
        StringRedisTemplate redisTemplate,
        @Value("${app.studio-app.max-calls-per-minute-per-ip:20}") long maxCallsPerMinute,
        @Value("${app.studio-app.max-ai-per-minute-per-ip:15}") long maxAiPerMinute,
        @Value("${app.studio-app.max-auth-per-minute-per-ip:10}") long maxAuthAttemptsPerMinute
    ) {
        this.redisTemplate = redisTemplate;
        this.maxCallsPerMinute = maxCallsPerMinute;
        this.maxAiPerMinute = maxAiPerMinute;
        this.maxAuthAttemptsPerMinute = maxAuthAttemptsPerMinute;
    }

    /** Agent/workflow invocations. */
    public boolean tryAcquireCall(String clientIp) {
        return tryAcquire("call:", clientIp, maxCallsPerMinute);
    }

    /** Hosted-AI turns. */
    public boolean tryAcquireAi(String clientIp) {
        return tryAcquire("ai:", clientIp, maxAiPerMinute);
    }

    /** Signup + login combined - the credential-touching paths get the tightest budget. */
    public boolean tryAcquireAuth(String clientIp) {
        return tryAcquire("auth:", clientIp, maxAuthAttemptsPerMinute);
    }

    private boolean tryAcquire(String bucket, String key, long limitPerMinute) {
        if (key == null || key.isBlank()) {
            return true;
        }
        // Used to fail OPEN on any Redis error, switching off the login
        // brute-force throttle exactly when Redis was struggling.
        return RedisFixedWindowCounter.tryAcquire(redisTemplate, PREFIX + bucket + key, limitPerMinute, Duration.ofMinutes(1));
    }
}
