package com.agenticai.core.tenancy.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link DemoSessionRateLimiter}: the exempt-IP allowlist, the fail-open-on-missing-Redis posture,
 * the INCR+EXPIRE fixed window itself, the Redis-down in-process fallback, and that every {@code
 * tryAcquire*} variant counts against its own key prefix (counters must never be shared - see the
 * prefix javadocs on the class).
 */
class DemoSessionRateLimiterTest {

    private ObjectProvider<StringRedisTemplate> redisProvider;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private PlatformSettingsService settings;
    private DemoSessionRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisProvider = mock(ObjectProvider.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        settings = mock(PlatformSettingsService.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Production reads every limit live: default stub answers each
        // getInt(key, default) with its own default, matching a platform
        // settings table with no rows.
        when(settings.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(settings.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        limiter =
                new DemoSessionRateLimiter(
                        redisProvider,
                        settings,
                        3 /* maxAttempts */,
                        1440 /* windowMinutes */,
                        6 /* imageMaxAttempts */,
                        1440,
                        2 /* videoMaxAttempts */,
                        1440,
                        6 /* transcriptEmailMaxAttempts */,
                        1440,
                        20 /* shareChatMaxAttempts */,
                        60,
                        5 /* shareReportMaxAttempts */,
                        1440);
        LocalRateLimitFallback.reset();
    }

    @AfterEach
    void tearDown() {
        LocalRateLimitFallback.reset();
    }

    @Test
    void anExemptIpIsAllowedWithoutEverTouchingRedis() {
        when(settings.getString("demo.rate-limit.exempt-ips", ""))
                .thenReturn("203.0.113.7, 198.51.100.9");

        assertTrue(limiter.tryAcquire("203.0.113.7"));

        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void aNonExemptIpCountsNormallyAgainstTheCreationWindow() {
        when(settings.getString("demo.rate-limit.exempt-ips", "")).thenReturn("203.0.113.7");
        when(valueOperations.increment("agentic:demo-session-rate:198.51.100.9")).thenReturn(1L);

        assertTrue(limiter.tryAcquire("198.51.100.9"));
        // First hit arms the 24h window.
        verify(redisTemplate)
                .expire("agentic:demo-session-rate:198.51.100.9", Duration.ofMinutes(1440));
    }

    @Test
    void countPastTheCapIsRejected() {
        when(valueOperations.increment(anyString())).thenReturn(4L); // cap is 3

        assertFalse(limiter.tryAcquire("198.51.100.9"));
    }

    @Test
    void missingRedisFailsOpen() {
        when(redisProvider.getIfAvailable()).thenReturn(null);

        assertTrue(limiter.tryAcquire("198.51.100.9"));
    }

    @Test
    void aRedisOutageStillLimitsInProcess() {
        when(valueOperations.increment(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        // Cap is 3 per window: three acquires pass in-process, the fourth
        // does not - fail-open would spend unbounded platform LLM budget.
        assertTrue(limiter.tryAcquire("198.51.100.9"));
        assertTrue(limiter.tryAcquire("198.51.100.9"));
        assertTrue(limiter.tryAcquire("198.51.100.9"));
        assertFalse(limiter.tryAcquire("198.51.100.9"));
    }

    @Test
    void everyAcquireVariantUsesItsOwnKeyPrefix() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertTrue(limiter.tryAcquireImage("10.0.0.1"));
        verify(valueOperations).increment("agentic:demo-image-rate:10.0.0.1");

        assertTrue(limiter.tryAcquireVideo("10.0.0.1"));
        verify(valueOperations).increment("agentic:demo-video-rate:10.0.0.1");

        assertTrue(limiter.tryAcquireMusic("10.0.0.1"));
        verify(valueOperations).increment("agentic:demo-music-rate:10.0.0.1");

        assertTrue(limiter.tryAcquireTranscriptEmail("10.0.0.1"));
        verify(valueOperations).increment("agentic:demo-transcript-email-rate:10.0.0.1");

        assertTrue(limiter.tryAcquireShareChat("10.0.0.1"));
        verify(valueOperations).increment("agentic:demo-share-chat-rate:10.0.0.1");

        assertTrue(limiter.tryAcquireShareReport("10.0.0.1"));
        verify(valueOperations).increment("agentic:demo-share-report-rate:10.0.0.1");
    }

    @Test
    void eachVariantHonoursItsOwnCap() {
        // Video's cap is 2/day by default - deliberately the stingiest, Veo
        // output is billed per second.
        when(valueOperations.increment("agentic:demo-video-rate:10.0.0.2")).thenReturn(3L);

        assertFalse(limiter.tryAcquireVideo("10.0.0.2"));
    }
}
