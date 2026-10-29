package com.agenticai.core.sharing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link ShareRateLimiter}: per {@code tenantId:subject} fixed windows with independent read and
 * write caps, no limiting at all without a tenant context (authentication is SecurityConfig's job,
 * not this throttle's), and the Redis-down degradation to the in-process limiter rather than
 * failing open.
 */
class ShareRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ShareRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Deliberately tiny caps so tests can walk past them.
        limiter = new ShareRateLimiter(redisTemplate, 2 /* writes/min */, 3 /* reads/min */);
        LocalRateLimitFallback.reset();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        LocalRateLimitFallback.reset();
    }

    private static void loginAs(String tenantId, String subject) {
        TenantContext.set(TenantContext.of(tenantId, subject, Set.of()));
    }

    @Test
    void noTenantContextNeverTouchesRedis() {
        assertDoesNotThrow(limiter::assertGrantWriteAllowed);
        assertDoesNotThrow(limiter::assertGrantReadAllowed);
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void writeAndReadCountAgainstDifferentKeysAndCaps() {
        loginAs("tenant-1", "alice");
        when(valueOperations.increment(anyString())).thenReturn(1L);

        limiter.assertGrantWriteAllowed();
        verify(valueOperations).increment("agentic:share-rate:write:tenant-1:alice");
        // First hit arms the one-minute window.
        verify(redisTemplate)
                .expire("agentic:share-rate:write:tenant-1:alice", Duration.ofMinutes(1));

        limiter.assertGrantReadAllowed();
        verify(valueOperations).increment("agentic:share-rate:read:tenant-1:alice");
    }

    @Test
    void aNullSubjectSharesTheTenantStarBucket() {
        loginAs("tenant-1", null);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        limiter.assertGrantWriteAllowed();

        verify(valueOperations).increment("agentic:share-rate:write:tenant-1:*");
    }

    @Test
    void overTheWriteCapThrowsThe429Exception() {
        loginAs("tenant-1", "alice");
        when(valueOperations.increment(anyString())).thenReturn(3L); // write cap is 2

        assertThrows(ShareRateLimitExceededException.class, limiter::assertGrantWriteAllowed);
    }

    @Test
    void overTheReadCapThrowsThe429Exception() {
        loginAs("tenant-1", "alice");
        when(valueOperations.increment(anyString())).thenReturn(4L); // read cap is 3

        assertThrows(ShareRateLimitExceededException.class, limiter::assertGrantReadAllowed);
    }

    @Test
    void aRedisOutageStillLimitsInProcess() {
        loginAs("tenant-1", "alice");
        when(valueOperations.increment(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        // Write cap 2: two acquires pass in-process, the third throws -
        // an enumeration oracle must not go fail-open.
        assertDoesNotThrow(limiter::assertGrantWriteAllowed);
        assertDoesNotThrow(limiter::assertGrantWriteAllowed);
        assertThrows(ShareRateLimitExceededException.class, limiter::assertGrantWriteAllowed);
    }

    @Test
    void eachCallersBucketIsIndependent() {
        loginAs("tenant-1", "alice");
        when(valueOperations.increment("agentic:share-rate:write:tenant-1:alice")).thenReturn(3L);
        when(valueOperations.increment("agentic:share-rate:write:tenant-1:bob")).thenReturn(1L);

        assertThrows(ShareRateLimitExceededException.class, limiter::assertGrantWriteAllowed);

        loginAs("tenant-1", "bob");
        assertDoesNotThrow(limiter::assertGrantWriteAllowed);
    }
}
