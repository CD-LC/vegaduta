package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The per-user ceiling on expression previews: generous (120/min), fixed window, never unlimited. */
class WorkflowExpressionRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        LocalRateLimitFallback.reset();
    }

    @AfterEach
    void tearDown() {
        LocalRateLimitFallback.reset();
    }

    @Test
    void theOneHundredTwentyFirstCallInAWindowIsRejectedWith429() {
        WorkflowExpressionRateLimiter limiter = new WorkflowExpressionRateLimiter(redis, 120);
        when(ops.increment(anyString())).thenReturn(120L, 121L);

        limiter.check(); // the 120th passes
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, limiter::check);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }

    @Test
    void theWindowIsStartedByTheFirstCallOnly() {
        WorkflowExpressionRateLimiter limiter = new WorkflowExpressionRateLimiter(redis, 120);
        when(ops.increment(anyString())).thenReturn(1L, 2L);

        limiter.check();
        limiter.check();

        verify(redis, times(1)).expire(anyString(), org.mockito.ArgumentMatchers.eq(java.time.Duration.ofMinutes(1)));
    }

    @Test
    void theDefaultLimitComesFromConfigurationAndIsNeverBelowOne() {
        WorkflowExpressionRateLimiter tiny = new WorkflowExpressionRateLimiter(redis, 0);
        when(ops.increment(anyString())).thenReturn(1L, 2L);
        tiny.check();
        assertThrows(ResponseStatusException.class, tiny::check);
    }

    @Test
    void whenRedisIsDownTheLimitStillAppliesInProcess() {
        WorkflowExpressionRateLimiter limiter = new WorkflowExpressionRateLimiter(redis, 3);
        when(ops.increment(anyString())).thenThrow(new IllegalStateException("redis down"));

        limiter.check();
        limiter.check();
        limiter.check();
        assertThrows(ResponseStatusException.class, limiter::check);
    }
}
