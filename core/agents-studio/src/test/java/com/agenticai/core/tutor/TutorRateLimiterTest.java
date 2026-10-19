package com.agenticai.core.tutor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TutorRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    @BeforeEach
    void setUp() {
        LocalRateLimitFallback.reset();
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void callsWithinTheWindowPassAndTheFirstSetsTheExpiry() {
        TutorRateLimiter limiter = new TutorRateLimiter(redis, 2);
        when(values.increment("agentic:tutor-generate:acme:alice")).thenReturn(1L, 2L);

        assertDoesNotThrow(() -> limiter.assertGenerationAllowed("acme", "alice"));
        assertDoesNotThrow(() -> limiter.assertGenerationAllowed("acme", "alice"));

        verify(redis).expire("agentic:tutor-generate:acme:alice", Duration.ofHours(1));
    }

    @Test
    void theCallPastTheCapIs429() {
        TutorRateLimiter limiter = new TutorRateLimiter(redis, 2);
        when(values.increment(anyString())).thenReturn(3L);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> limiter.assertGenerationAllowed("acme", "alice"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
    }

    @Test
    void redisDownFallsBackToTheInProcessLimiterInsteadOfFailingOpen() {
        TutorRateLimiter limiter = new TutorRateLimiter(redis, 1);
        when(values.increment(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> limiter.assertGenerationAllowed("acme", "bob"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> limiter.assertGenerationAllowed("acme", "bob"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
    }

    @Test
    void aBlankSubjectIsNotCounted() {
        TutorRateLimiter limiter = new TutorRateLimiter(redis, 1);

        assertDoesNotThrow(() -> limiter.assertGenerationAllowed("acme", " "));

        verify(values, never()).increment(anyString());
    }
}
