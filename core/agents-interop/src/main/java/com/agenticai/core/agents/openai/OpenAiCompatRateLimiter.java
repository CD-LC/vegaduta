package com.agenticai.core.agents.openai;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window per-caller request limit for the OpenAI-compatible endpoint.
 * Redis {@code INCR} + {@code EXPIRE} so the limit holds across pods; when Redis
 * is absent or errors it falls back to a per-pod in-memory window rather than
 * failing open, because every request on this surface spends model budget.
 * Spend itself is capped separately by the per-user budget inside the chat
 * service - this only bounds request rate.
 */
@Component
@ConditionalOnProperty(prefix = "app.openai-compat", name = "enabled", havingValue = "true")
public class OpenAiCompatRateLimiter {

    private static final String KEY_PREFIX = "agentic:openai-compat-rate:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final int maxRequests;
    private final long windowSeconds;
    private final ConcurrentHashMap<String, long[]> local = new ConcurrentHashMap<>();

    public OpenAiCompatRateLimiter(
        ObjectProvider<StringRedisTemplate> redisProvider,
        @Value("${app.openai-compat.rate-limit.max-requests:60}") int maxRequests,
        @Value("${app.openai-compat.rate-limit.window-seconds:60}") long windowSeconds
    ) {
        this.redisProvider = redisProvider;
        this.maxRequests = Math.max(1, maxRequests);
        this.windowSeconds = Math.max(1L, windowSeconds);
    }

    /** @return 0 when the request is allowed, else the seconds the caller should wait. */
    public long tryAcquire(String callerKey) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                String key = KEY_PREFIX + callerKey;
                Long count = redis.opsForValue().increment(key);
                if (count != null) {
                    if (count == 1L) {
                        redis.expire(key, Duration.ofSeconds(windowSeconds));
                    }
                    if (count <= maxRequests) {
                        return 0L;
                    }
                    Long ttl = redis.getExpire(key);
                    return ttl != null && ttl > 0 ? ttl : windowSeconds;
                }
            } catch (RuntimeException e) {
                // fall through to the local window
            }
        }
        return tryAcquireLocal(callerKey, System.currentTimeMillis());
    }

    long tryAcquireLocal(String callerKey, long nowMillis) {
        long[] slot = local.compute(callerKey, (k, cur) -> {
            if (cur == null || nowMillis - cur[0] >= windowSeconds * 1000L) {
                return new long[] {nowMillis, 1L};
            }
            cur[1]++;
            return cur;
        });
        if (slot[1] <= maxRequests) {
            return 0L;
        }
        long remainingMs = windowSeconds * 1000L - (nowMillis - slot[0]);
        return Math.max(1L, (remainingMs + 999L) / 1000L);
    }
}
