package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** The fixed-window per-key limiter behind the public form and the Wait resume URL. */
class WorkflowPublicRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private WorkflowPublicRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        limiter = new WorkflowPublicRateLimiter(redis);
    }

    @Test
    void allowsUpToTheLimitThenRefuses() {
        when(ops.increment("p:k")).thenReturn(1L, 2L, 3L, 4L);

        assertTrue(limiter.tryAcquire("p:", "k", 3));
        assertTrue(limiter.tryAcquire("p:", "k", 3));
        assertTrue(limiter.tryAcquire("p:", "k", 3));
        assertFalse(limiter.tryAcquire("p:", "k", 3), "the fourth hit in the window is over a limit of three");
    }

    @Test
    void theWindowExpiryIsSetOnlyByTheFirstHit() {
        when(ops.increment("p:k")).thenReturn(1L, 2L);

        limiter.tryAcquire("p:", "k", 10);
        limiter.tryAcquire("p:", "k", 10);

        verify(redis, times(1)).expire("p:k", Duration.ofMinutes(1));
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(ops.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        assertTrue(limiter.tryAcquire("p:", "k", 1));
    }

    @Test
    void aBlankKeyOrANonPositiveLimitIsNeverCounted() {
        assertTrue(limiter.tryAcquire("p:", "", 5));
        assertTrue(limiter.tryAcquire("p:", null, 5));
        assertTrue(limiter.tryAcquire("p:", "k", 0));

        verify(ops, never()).increment(anyString());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
