package com.agenticai.core.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisFixedWindowCounterTest {

    /** Hand-rolled stub (no Mockito): returns a scripted count, or throws when count is null. */
    static final class StubRedis extends StringRedisTemplate {
        Long next;
        List<String> lastKeys;
        Object[] lastArgs;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            lastKeys = keys;
            lastArgs = args;
            if (next == null) {
                throw new IllegalStateException("redis down");
            }
            return (T) next;
        }
    }

    @BeforeEach
    void clearFallback() {
        LocalRateLimitFallback.reset();
    }

    @Test
    void usesTheRedisCountWithTheWindowAsTtl() {
        StubRedis redis = new StubRedis();
        redis.next = 3L;
        assertTrue(RedisFixedWindowCounter.tryAcquire(redis, "k", 3, Duration.ofMinutes(2)));
        assertEquals(List.of("k"), redis.lastKeys);
        assertEquals("120", redis.lastArgs[0]);
        redis.next = 4L;
        assertFalse(RedisFixedWindowCounter.tryAcquire(redis, "k", 3, Duration.ofMinutes(2)));
    }

    @Test
    void keepsLimitingInProcessWhenRedisErrors() {
        StubRedis redis = new StubRedis();
        for (int i = 0; i < 2; i++) {
            assertTrue(RedisFixedWindowCounter.tryAcquire(redis, "down", 2, Duration.ofMinutes(1)));
        }
        assertFalse(RedisFixedWindowCounter.tryAcquire(redis, "down", 2, Duration.ofMinutes(1)));
    }

    @Test
    void keepsLimitingInProcessWithNoRedisAtAll() {
        assertTrue(RedisFixedWindowCounter.tryAcquire(null, "none", 1, Duration.ofMinutes(1)));
        assertFalse(RedisFixedWindowCounter.tryAcquire(null, "none", 1, Duration.ofMinutes(1)));
    }
}
