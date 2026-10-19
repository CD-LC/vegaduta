package com.agenticai.core.agents.vision;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantPlan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The per-user-per-day fixed windows (live-vision shared contract items 4/5):
 * plan-scoped caps from {@link VisionProperties}, the INCR-then-compare 429
 * path, first-write-sets-expiry, and the fail-open-on-Redis-outage posture -
 * all mirroring {@code FreeTierUsageLimiterService}'s documented behavior.
 */
class VisionUsageServiceTest {

    private static final String FRAMES_KEY = "agentic:vision-frames:daily:acme:user-1";
    private static final String MINUTES_KEY = "agentic:vision-watch-minutes:daily:acme:user-1";

    private ValueOperations<String, String> valueOperations;
    private StringRedisTemplate redisTemplate;
    private VisionProperties properties;
    private VisionUsageService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        properties = new VisionProperties(); // contract defaults: 10 / 500 / 120
        service = new VisionUsageService(redisTemplate, properties, new SimpleMeterRegistry());
    }

    // --- plan-scoped caps ---

    @Test
    void plansSelectTheirConfiguredCaps() {
        assertEquals(10, service.framesDailyCapFor(TenantPlan.FREE));
        assertEquals(500, service.framesDailyCapFor(TenantPlan.PREMIUM));
        assertEquals(500, service.framesDailyCapFor(TenantPlan.UNLIMITED));
        assertEquals(0, service.watchMinutesDailyCapFor(TenantPlan.FREE));
        assertEquals(120, service.watchMinutesDailyCapFor(TenantPlan.PREMIUM));
    }

    // --- recordFrames window mechanics ---

    @Test
    void framesUnderTheCapAreConsumedWithoutThrowing() {
        when(valueOperations.increment(FRAMES_KEY, 2L)).thenReturn(7L);

        assertDoesNotThrow(() -> service.recordFrames("acme", "user-1", TenantPlan.FREE, 2));
    }

    @Test
    void frameOverTheFreeCapThrowsWithTheLiveConfiguredLimitInTheMessage() {
        properties.getFrames().setFreeDaily(3);
        when(valueOperations.increment(FRAMES_KEY, 1L)).thenReturn(4L);

        VisionQuotaExceededException e = assertThrows(VisionQuotaExceededException.class,
            () -> service.recordFrames("acme", "user-1", TenantPlan.FREE, 1));
        // Admin-tuned number reaches the user verbatim - no hardcoded copy.
        assertTrue(e.getMessage().contains("3 frames/day"));
    }

    @Test
    void paidPlanUsesThePremiumCapNotTheFreeOne() {
        // 11th frame of the day - over FREE's 10, well under PREMIUM's 500.
        when(valueOperations.increment(FRAMES_KEY, 1L)).thenReturn(11L);

        assertDoesNotThrow(() -> service.recordFrames("acme", "user-1", TenantPlan.PREMIUM, 1));
    }

    @Test
    void firstWriteOfTheWindowSetsTheDailyExpiryAndLaterWritesDoNot() {
        when(valueOperations.increment(FRAMES_KEY, 2L)).thenReturn(2L);
        service.recordFrames("acme", "user-1", TenantPlan.FREE, 2);
        verify(redisTemplate).expire(FRAMES_KEY, Duration.ofDays(1));

        when(valueOperations.increment(FRAMES_KEY, 3L)).thenReturn(5L);
        service.recordFrames("acme", "user-1", TenantPlan.FREE, 3);
        // Still exactly the one expire() call from the window-creating write.
        verify(redisTemplate).expire(anyString(), any(Duration.class));
    }

    @Test
    void zeroOrNegativeFramesAreANoOp() {
        service.recordFrames("acme", "user-1", TenantPlan.FREE, 0);
        service.recordFrames("acme", "user-1", TenantPlan.FREE, -4);

        verify(valueOperations, never()).increment(anyString(), anyLong());
    }

    @Test
    void redisOutageFailsOpenRatherThanBlockingTheTurn() {
        // increment() returns null when Redis is unreachable - metering is a
        // spend throttle, not billing correctness (see the service javadoc).
        when(valueOperations.increment(FRAMES_KEY, 1L)).thenReturn(null);

        assertDoesNotThrow(() -> service.recordFrames("acme", "user-1", TenantPlan.FREE, 1));
    }

    // --- framesRemaining (read-only allowance) ---

    @Test
    void framesRemainingSubtractsUsageAndNeverGoesNegative() {
        when(valueOperations.get(FRAMES_KEY)).thenReturn("4");
        assertEquals(6, service.framesRemaining("acme", "user-1", TenantPlan.FREE));

        when(valueOperations.get(FRAMES_KEY)).thenReturn("40");
        assertEquals(0, service.framesRemaining("acme", "user-1", TenantPlan.FREE));

        verify(valueOperations, never()).increment(anyString(), anyLong());
    }

    @Test
    void framesRemainingTreatsMissingAndCorruptCountersAsZeroUsed() {
        when(valueOperations.get(FRAMES_KEY)).thenReturn(null);
        assertEquals(10, service.framesRemaining("acme", "user-1", TenantPlan.FREE));

        when(valueOperations.get(FRAMES_KEY)).thenReturn("not-a-number");
        assertEquals(10, service.framesRemaining("acme", "user-1", TenantPlan.FREE));
    }

    // --- watch minutes ---

    @Test
    void watchMinutesHeadroomThrowsOnceTheDailyCapIsUsedUp() {
        properties.getWatch().setMinutesPremiumDaily(60);
        when(valueOperations.get(MINUTES_KEY)).thenReturn("60");

        VisionQuotaExceededException e = assertThrows(VisionQuotaExceededException.class,
            () -> service.checkWatchMinutesHeadroom("acme", "user-1", TenantPlan.PREMIUM));
        assertTrue(e.getMessage().contains("60 minutes/day"));
    }

    @Test
    void watchMinutesHeadroomPassesWithRoomLeft() {
        when(valueOperations.get(MINUTES_KEY)).thenReturn("119");

        assertDoesNotThrow(() -> service.checkWatchMinutesHeadroom("acme", "user-1", TenantPlan.PREMIUM));
    }

    @Test
    void recordWatchMinutesAddsToTheWindowAndNeverThrows() {
        when(valueOperations.increment(MINUTES_KEY, 5L)).thenReturn(125L);

        // Over the cap - but the minutes are already spent; only the NEXT
        // start is blocked (checkWatchMinutesHeadroom), same shape as
        // FreeTierUsageLimiterService#recordTokenUsage.
        assertDoesNotThrow(() -> service.recordWatchMinutes("acme", "user-1", 5));
        verify(valueOperations).increment(eq(MINUTES_KEY), eq(5L));
    }
}
