package com.agenticai.core.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Regression coverage for the SAST audit fix (2026-07-27): {@code POST
 * /api/devices/claim} had no rate limit at all on a permitAll(), six-digit-
 * code-guarded endpoint. See {@link DeviceClaimRateLimiter}'s javadoc for the
 * full threat model this closes.
 */
class DeviceClaimRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private PlatformSettingsService platformSettingsService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        redisTemplateProvider = mock(ObjectProvider.class);
        platformSettingsService = mock(PlatformSettingsService.class);

        when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Live-tunable settings fall back to the constructor default in every test here.
        when(platformSettingsService.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt()))
            .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private DeviceClaimRateLimiter limiter() {
        return new DeviceClaimRateLimiter(redisTemplateProvider, platformSettingsService, 3, 10, 2, 10);
    }

    @Test
    void allowsCallsUnderBothThePerIpAndPerCodeLimit() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertThat(limiter().tryAcquire("1.2.3.4", "000000")).isTrue();
    }

    @Test
    void blocksOnceThePerIpBucketIsExhaustedEvenWithADifferentCodeEachTime() {
        DeviceClaimRateLimiter limiter = limiter();
        // Per-IP max is 3; per-code max is 2, but a fresh code each call keeps
        // the code bucket from being the one that trips first.
        when(valueOperations.increment("agentic:device-claim-rate:ip:1.2.3.4")).thenReturn(1L, 2L, 3L, 4L);
        when(valueOperations.increment(org.mockito.ArgumentMatchers.startsWith("agentic:device-claim-rate:code:")))
            .thenReturn(1L);

        assertThat(limiter.tryAcquire("1.2.3.4", "000001")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4", "000002")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4", "000003")).isTrue();
        // Fourth attempt from the same IP within the window exceeds max-attempts-per-ip (3).
        assertThat(limiter.tryAcquire("1.2.3.4", "000004")).isFalse();
    }

    @Test
    void blocksOnceThePerCodeBucketIsExhaustedEvenFromDifferentIps() {
        DeviceClaimRateLimiter limiter = limiter();
        // Per-code max is 2 - the same guessed code retried from a pool of IPs must still trip.
        when(valueOperations.increment(org.mockito.ArgumentMatchers.startsWith("agentic:device-claim-rate:ip:")))
            .thenReturn(1L);
        when(valueOperations.increment("agentic:device-claim-rate:code:123456")).thenReturn(1L, 2L, 3L);

        assertThat(limiter.tryAcquire("1.1.1.1", "123456")).isTrue();
        assertThat(limiter.tryAcquire("2.2.2.2", "123456")).isTrue();
        // Third guess of this exact code, from yet another address, exceeds max-attempts-per-code (2).
        assertThat(limiter.tryAcquire("3.3.3.3", "123456")).isFalse();
    }

    @Test
    void setsExpiryOnlyOnTheFirstIncrementOfAWindow() {
        DeviceClaimRateLimiter limiter = limiter();
        when(valueOperations.increment(anyString())).thenReturn(1L);

        limiter.tryAcquire("9.9.9.9", "555555");

        org.mockito.Mockito.verify(redisTemplate).expire("agentic:device-claim-rate:ip:9.9.9.9", Duration.ofMinutes(10));
        org.mockito.Mockito.verify(redisTemplate).expire("agentic:device-claim-rate:code:555555", Duration.ofMinutes(10));
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(null);

        assertThat(limiter().tryAcquire("1.2.3.4", "000000")).isTrue();
    }

    @Test
    void failsOpenWhenRedisThrows() {
        when(valueOperations.increment(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        assertThat(limiter().tryAcquire("1.2.3.4", "000000")).isTrue();
    }

    @Test
    void nullSubmittedCodeIsBucketedRatherThanRejected() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertThat(limiter().tryAcquire("1.2.3.4", null)).isTrue();
        org.mockito.Mockito.verify(valueOperations).increment("agentic:device-claim-rate:code:");
    }
}
