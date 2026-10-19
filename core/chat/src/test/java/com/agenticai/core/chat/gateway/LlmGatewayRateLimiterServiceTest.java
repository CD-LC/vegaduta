package com.agenticai.core.chat.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link LlmGatewayRateLimiterService} is the "screen before it reaches the
 * LLM" pre-call check from #848's gateway seam - the only thing standing
 * between a runaway agent loop and unbounded per-minute call volume on a
 * tenant's own key. Moved off a per-instance {@code ConcurrentHashMap} to a
 * Redis-backed counter per Master Roadmap 2026 Section2.1 (the in-memory state
 * blocked running more than one replica); this suite was rewritten alongside
 * that change to the same {@link StringRedisTemplate}/{@link ValueOperations}
 * manual-mock convention {@code WorkflowTriggerRateLimiterTest} (this class's
 * closest sibling - same fixed-window-counter-plus-fail-open shape) already
 * established, rather than the old constructor-takes-the-limit-directly
 * approach.
 */
class LlmGatewayRateLimiterServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private TenantLimits tenantLimits;
    // Infra audit (2026-07-27) - real SimpleMeterRegistry (not a mock), same
    // "cheap, in-memory, no Prometheus backend needed" choice as any other
    // Micrometer-instrumented class's own test suite in this codebase would
    // make - lets the rejection-counter increment actually happen without
    // throwing, without needing to assert on it in every existing test here.
    private MeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // The Redis-down path now counts in a process-wide in-memory table
        // (LocalRateLimitFallback); start each test from an empty one.
        com.agenticai.core.security.ratelimit.LocalRateLimitFallback.reset();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        tenantLimits = mock(TenantLimits.class);
        // PREMIUM by default so every pre-existing test below (each passing a
        // single flat "the limit" via limiterWithLimit) keeps exercising the
        // exact same ceiling regardless of which plan tier resolves it.
        when(tenantLimits.plan(anyString())).thenReturn(TenantPlan.PREMIUM);
        meterRegistry = new SimpleMeterRegistry();
    }

    /** Same ceiling for every plan tier - lets pre-existing tests ignore plan-scoping entirely. */
    private LlmGatewayRateLimiterService limiterWithLimit(long maxCallsPerWindow) {
        return new LlmGatewayRateLimiterService(
            redisTemplate, tenantLimits, maxCallsPerWindow, maxCallsPerWindow, maxCallsPerWindow, meterRegistry,
            new GovernanceDegradationSignal(meterRegistry));
    }

    @Test
    void callsWithinTheLimitDoNotThrow() {
        LlmGatewayRateLimiterService limiter = limiterWithLimit(3);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenReturn(1L, 2L, 3L);

        assertDoesNotThrow(() -> {
            limiter.assertWithinRateLimit("tenant-a");
            limiter.assertWithinRateLimit("tenant-a");
            limiter.assertWithinRateLimit("tenant-a");
        });
    }

    @Test
    void theCallThatExceedsTheLimitThrows() {
        LlmGatewayRateLimiterService limiter = limiterWithLimit(2);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenReturn(1L, 2L, 3L);

        limiter.assertWithinRateLimit("tenant-a");
        limiter.assertWithinRateLimit("tenant-a");

        assertThrows(LlmGatewayLimitExceededException.class, () -> limiter.assertWithinRateLimit("tenant-a"));
    }

    @Test
    void exceptionMessageMentionsTheConfiguredLimit() {
        LlmGatewayRateLimiterService limiter = limiterWithLimit(1);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenReturn(1L, 2L);
        limiter.assertWithinRateLimit("tenant-a");

        LlmGatewayLimitExceededException ex = assertThrows(
            LlmGatewayLimitExceededException.class, () -> limiter.assertWithinRateLimit("tenant-a"));

        assertTrue(ex.getMessage().contains("1"), "expected the configured limit (1) to appear in the message: " + ex.getMessage());
    }

    @Test
    void eachTenantHasAnIndependentCounter() {
        // A shared global counter would be a real cross-tenant bug here -
        // one noisy tenant must never throttle a different tenant. Redis
        // keys are already tenant-scoped (KEY_PREFIX + tenantId), so this
        // also guards against a key-construction regression collapsing two
        // tenants onto the same key.
        LlmGatewayRateLimiterService limiter = limiterWithLimit(1);
        when(valueOperations.increment("agentic:llm-gateway-rate:tenant-a")).thenReturn(1L, 2L);
        when(valueOperations.increment("agentic:llm-gateway-rate:tenant-b")).thenReturn(1L);

        limiter.assertWithinRateLimit("tenant-a");
        assertThrows(LlmGatewayLimitExceededException.class, () -> limiter.assertWithinRateLimit("tenant-a"));

        // tenant-b's own counter must still be fresh.
        assertDoesNotThrow(() -> limiter.assertWithinRateLimit("tenant-b"));
    }

    @Test
    void nullTenantIdSkipsTheCheckEntirelyRegardlessOfVolume() {
        // Internal/system calls (no TenantContext) must never be throttled -
        // there is no per-tenant counter to attribute them to, and sharing
        // one global counter across every tenant-less caller would be wrong.
        LlmGatewayRateLimiterService limiter = limiterWithLimit(1);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10; i++) {
                limiter.assertWithinRateLimit(null);
            }
        });

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void zeroCallLimitRejectsTheVeryFirstCall() {
        LlmGatewayRateLimiterService limiter = limiterWithLimit(0);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertThrows(LlmGatewayLimitExceededException.class, () -> limiter.assertWithinRateLimit("tenant-a"));
    }

    @Test
    void assertWithinRateLimitSetsTheWindowExpiryOnlyOnTheFirstIncrementOfAWindow() {
        LlmGatewayRateLimiterService limiter = limiterWithLimit(20);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenReturn(1L);

        limiter.assertWithinRateLimit("tenant-a");

        verify(redisTemplate).expire(key, Duration.ofMinutes(1));
    }

    @Test
    void assertWithinRateLimitDoesNotReExpireOnEverySubsequentIncrementInTheSameWindow() {
        LlmGatewayRateLimiterService limiter = limiterWithLimit(20);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenReturn(5L);

        limiter.assertWithinRateLimit("tenant-a");

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void assertWithinRateLimitFailsOpenWhenRedisReturnsANullCount() {
        LlmGatewayRateLimiterService limiter = limiterWithLimit(1);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenReturn(null);

        assertDoesNotThrow(() -> limiter.assertWithinRateLimit("tenant-a"));
        verify(redisTemplate, never()).expire(eq(key), any(Duration.class));
    }

    @Test
    void assertWithinRateLimitFailsOpenWhenIncrementThrowsARuntimeException() {
        // The multi-replica fix this class exists for (Section2.1) must not trade
        // "wrong under >1 replica" for "throws whenever Redis has a hiccup" -
        // same fail-open posture as WorkflowTriggerRateLimiter/
        // InboundMessageDedupService for the identical Redis-unreachable case.
        LlmGatewayRateLimiterService limiter = limiterWithLimit(1);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> limiter.assertWithinRateLimit("tenant-a"));
        verify(redisTemplate, never()).expire(eq(key), any(Duration.class));
    }

    // ---- plan-scoped ceiling (infra audit 2026-07-27) -----------------------

    @Test
    void freeTenantIsCappedAtTheFreeCeiling() {
        LlmGatewayRateLimiterService limiter = new LlmGatewayRateLimiterService(
            redisTemplate, tenantLimits, 30L, 150L, 600L, meterRegistry,
            new GovernanceDegradationSignal(meterRegistry));
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.FREE);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenReturn(31L);

        assertThrows(LlmGatewayLimitExceededException.class, () -> limiter.assertWithinRateLimit("tenant-a"));
    }

    @Test
    void premiumTenantGetsTheMidCeilingNotTheFreeOne() {
        LlmGatewayRateLimiterService limiter = new LlmGatewayRateLimiterService(
            redisTemplate, tenantLimits, 30L, 150L, 600L, meterRegistry,
            new GovernanceDegradationSignal(meterRegistry));
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        String key = "agentic:llm-gateway-rate:tenant-a";
        // Above the FREE ceiling (30) but within the PREMIUM ceiling (150) -
        // must NOT throw, proving the plan (not the FREE default) governs.
        when(valueOperations.increment(key)).thenReturn(31L);

        assertDoesNotThrow(() -> limiter.assertWithinRateLimit("tenant-a"));
    }

    @Test
    void unlimitedTenantGetsTheHighCeiling() {
        LlmGatewayRateLimiterService limiter = new LlmGatewayRateLimiterService(
            redisTemplate, tenantLimits, 30L, 150L, 600L, meterRegistry,
            new GovernanceDegradationSignal(meterRegistry));
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.UNLIMITED);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenReturn(300L);

        assertDoesNotThrow(() -> limiter.assertWithinRateLimit("tenant-a"));
    }

    @Test
    void assertWithinRateLimitFailsOpenWhenPlanLookupThrows() {
        LlmGatewayRateLimiterService limiter = limiterWithLimit(1);
        when(tenantLimits.plan("tenant-a")).thenThrow(new RuntimeException("tenant lookup failed"));

        assertDoesNotThrow(() -> limiter.assertWithinRateLimit("tenant-a"));
        // The check skipped entirely before ever touching Redis.
        verify(redisTemplate, never()).opsForValue();
    }

    // ---- governance fail-open observability (2026-08-07) --------------------

    /** The fail-open counter for this limiter, on the suite's shared registry. */
    private double failOpenCount() {
        return meterRegistry
            .counter(GovernanceDegradationSignal.METRIC_NAME, "component", "llm-gateway-rate-limiter")
            .count();
    }

    @Test
    void everyFailOpenBranchReportsToTheDegradationSignal() {
        // Before this, each fail-open branch left at most a log line - a Redis
        // blip disabling this limiter (and every sibling governance seam)
        // platform-wide was invisible to dashboards/alerts.
        LlmGatewayRateLimiterService limiter = limiterWithLimit(1);

        // Branch 1: plan lookup fails.
        when(tenantLimits.plan("tenant-a")).thenThrow(new RuntimeException("tenant lookup failed"));
        limiter.assertWithinRateLimit("tenant-a");
        assertEquals(1.0, failOpenCount());

        // Branch 2: Redis INCR throws.
        when(tenantLimits.plan("tenant-b")).thenReturn(TenantPlan.PREMIUM);
        when(valueOperations.increment("agentic:llm-gateway-rate:tenant-b"))
            .thenThrow(new RedisConnectionFailureException("down"));
        limiter.assertWithinRateLimit("tenant-b");
        assertEquals(2.0, failOpenCount());

        // Branch 3: reachable-but-odd Redis returns a null count.
        when(tenantLimits.plan("tenant-c")).thenReturn(TenantPlan.PREMIUM);
        when(valueOperations.increment("agentic:llm-gateway-rate:tenant-c")).thenReturn(null);
        limiter.assertWithinRateLimit("tenant-c");
        assertEquals(3.0, failOpenCount());
    }

    @Test
    void aRejectedCallIsEnforcementNotDegradationAndReportsNothing() {
        LlmGatewayRateLimiterService limiter = limiterWithLimit(1);
        String key = "agentic:llm-gateway-rate:tenant-a";
        when(valueOperations.increment(key)).thenReturn(2L);

        assertThrows(LlmGatewayLimitExceededException.class, () -> limiter.assertWithinRateLimit("tenant-a"));

        assertEquals(0.0, failOpenCount());
    }
}
