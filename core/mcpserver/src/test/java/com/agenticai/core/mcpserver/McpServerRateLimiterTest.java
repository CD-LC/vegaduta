package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link McpServerRateLimiter} is the only abuse throttle in front of a
 * public, key-authenticated surface that can trigger real agent turns and
 * whole workflow runs - and it deliberately fails OPEN when Redis is down
 * (an outage must degrade to "unthrottled", never "every key 429s"). Both
 * halves of that trade-off are pinned here, following the exact {@link
 * StringRedisTemplate}/{@link ValueOperations} manual-mock convention its
 * explicitly named sibling {@code LlmGatewayRateLimiterServiceTest} (same
 * INCR + conditional EXPIRE fixed-window shape) already established.
 */
class McpServerRateLimiterTest {

    private static final String KEY_PREFIX = "agentic:mcpserver-rate:";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    // Real SimpleMeterRegistry + real GovernanceDegradationSignal (not mocks),
    // same choice LlmGatewayRateLimiterServiceTest made when the signal was
    // threaded into ITS constructor - lets the fail-open tests read the actual
    // governance_fail_open_total counter instead of verifying a mock call.
    private MeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        meterRegistry = new SimpleMeterRegistry();
    }

    private McpServerRateLimiter limiterWithLimit(long maxCallsPerWindow) {
        return new McpServerRateLimiter(
            redisTemplate, new GovernanceDegradationSignal(meterRegistry), maxCallsPerWindow);
    }

    /** The fail-open counter for this limiter's component tag, on the suite's shared registry. */
    private double failOpenCount() {
        return meterRegistry
            .counter(GovernanceDegradationSignal.METRIC_NAME, "component", "mcpserver-rate-limiter")
            .count();
    }

    @Test
    void callsWithinTheLimitAreAllowed() {
        McpServerRateLimiter limiter = limiterWithLimit(3);
        UUID grantId = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + grantId)).thenReturn(1L, 2L, 3L);

        assertTrue(limiter.allow(grantId));
        assertTrue(limiter.allow(grantId));
        assertTrue(limiter.allow(grantId));
    }

    @Test
    void theCallThatExceedsTheLimitIsRejected() {
        McpServerRateLimiter limiter = limiterWithLimit(2);
        UUID grantId = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + grantId)).thenReturn(1L, 2L, 3L);

        assertTrue(limiter.allow(grantId));
        assertTrue(limiter.allow(grantId));
        assertFalse(limiter.allow(grantId));
    }

    @Test
    void theCountExactlyAtTheLimitIsStillAllowed() {
        // Boundary contract: "max calls per minute" is inclusive (count <=
        // max). An off-by-one here silently shrinks every tenant's paid-for
        // ceiling by one call.
        McpServerRateLimiter limiter = limiterWithLimit(60);
        UUID grantId = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + grantId)).thenReturn(60L);

        assertTrue(limiter.allow(grantId));
    }

    @Test
    void eachGrantHasAnIndependentCounter() {
        // Keyed by grant id, deliberately NOT tenant id (class javadoc) - two
        // keys issued by the same tenant must not share one bucket, and one
        // noisy caller must never throttle a different grant.
        McpServerRateLimiter limiter = limiterWithLimit(1);
        UUID grantA = UUID.randomUUID();
        UUID grantB = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + grantA)).thenReturn(2L);
        when(valueOperations.increment(KEY_PREFIX + grantB)).thenReturn(1L);

        assertFalse(limiter.allow(grantA));
        assertTrue(limiter.allow(grantB));
    }

    @Test
    void windowExpiryIsSetOnlyOnTheFirstIncrementOfAWindow() {
        // The EXPIRE on count==1 is what makes this a fixed WINDOW at all -
        // never setting it turns a transient burst into a permanent
        // lockout; re-setting it on every call would slide the window and
        // never let a steady caller recover.
        McpServerRateLimiter limiter = limiterWithLimit(60);
        UUID grantId = UUID.randomUUID();
        String key = KEY_PREFIX + grantId;
        when(valueOperations.increment(key)).thenReturn(1L);

        limiter.allow(grantId);

        verify(redisTemplate).expire(key, Duration.ofMinutes(1));
    }

    @Test
    void windowExpiryIsNotReSetOnSubsequentIncrements() {
        McpServerRateLimiter limiter = limiterWithLimit(60);
        UUID grantId = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + grantId)).thenReturn(5L);

        limiter.allow(grantId);

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void failsOpenWhenRedisIsUnreachable() {
        // Deliberate trade-off from the class javadoc: Redis being down must
        // degrade this surface to "unthrottled", not turn every valid API
        // key into a 429 outage.
        McpServerRateLimiter limiter = limiterWithLimit(1);
        UUID grantId = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + grantId))
            .thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(limiter.allow(grantId));
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        // Failing open must also be OBSERVABLE (governance_fail_open_total,
        // component "mcpserver-rate-limiter") - a Redis blip silently
        // disabling the only throttle on this public surface is exactly what
        // GovernanceDegradationSignal exists to surface.
        assertEquals(1.0, failOpenCount());
    }

    @Test
    void failsOpenWhenRedisReturnsANullCount() {
        // Reachable-but-odd Redis (e.g. a pipelined/transactional null) gets
        // the same benefit of the doubt as an outright connection failure.
        McpServerRateLimiter limiter = limiterWithLimit(1);
        UUID grantId = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + grantId)).thenReturn(null);

        assertTrue(limiter.allow(grantId));
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        // The null-count branch has no exception, but it is still a skipped
        // check - it must report to the degradation signal all the same.
        assertEquals(1.0, failOpenCount());
    }

    @Test
    void zeroLimitRejectsTheVeryFirstCall() {
        // An admin setting max-calls-per-minute to 0 is expressing "shut this
        // surface off" - the first INCR returns 1 > 0 and must be rejected.
        McpServerRateLimiter limiter = limiterWithLimit(0);
        UUID grantId = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + grantId)).thenReturn(1L);

        assertFalse(limiter.allow(grantId));
    }

    @Test
    void aRejectedCallIsEnforcementNotDegradationAndReportsNothing() {
        // Mirror of the same guard in LlmGatewayRateLimiterServiceTest: a 429
        // is the limiter WORKING - it must never inflate the fail-open metric,
        // or the degradation alert fires on every abusive caller.
        McpServerRateLimiter limiter = limiterWithLimit(1);
        UUID grantId = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + grantId)).thenReturn(2L);

        assertFalse(limiter.allow(grantId));
        assertEquals(0.0, failOpenCount());
    }

    @Test
    void taskPollsUseTheirOwnLargerBucketAndNeverTouchTheGeneralOne() {
        McpServerRateLimiter limiter = new McpServerRateLimiter(
            redisTemplate, new GovernanceDegradationSignal(meterRegistry), 1, 5);
        UUID grantId = UUID.randomUUID();
        when(valueOperations.increment(KEY_PREFIX + "poll:" + grantId)).thenReturn(5L, 6L);

        assertTrue(limiter.allowPoll(grantId));
        assertFalse(limiter.allowPoll(grantId));
        org.mockito.Mockito.verify(valueOperations, org.mockito.Mockito.never()).increment(KEY_PREFIX + grantId);
    }
}
