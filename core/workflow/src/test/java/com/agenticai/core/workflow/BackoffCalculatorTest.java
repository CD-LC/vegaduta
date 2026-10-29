package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link BackoffCalculator}: exponential growth, the ceiling, the floor and full jitter. */
class BackoffCalculatorTest {

    @Test
    void capMillisDoublesEachAttempt() {
        assertEquals(500L, BackoffCalculator.capMillis(1));
        assertEquals(1_000L, BackoffCalculator.capMillis(2));
        assertEquals(2_000L, BackoffCalculator.capMillis(3));
        assertEquals(4_000L, BackoffCalculator.capMillis(4));
        assertEquals(8_000L, BackoffCalculator.capMillis(5));
    }

    @Test
    void capMillisIsCappedAtTenSeconds() {
        assertEquals(10_000L, BackoffCalculator.capMillis(6));
        assertEquals(10_000L, BackoffCalculator.capMillis(12));
        assertEquals(10_000L, BackoffCalculator.capMillis(100));
    }

    @Test
    void attemptZeroAndNonPositiveAttemptsKeepTheBase() {
        assertEquals(500L, BackoffCalculator.capMillis(0));
        assertEquals(500L, BackoffCalculator.capMillis(-3));
    }

    @Test
    void jitteredDelayMillisPassesInclusiveBoundToTheGenerator() {
        assertEquals(501L, BackoffCalculator.jitteredDelayMillis(1, bound -> bound));
        assertEquals(0L, BackoffCalculator.jitteredDelayMillis(1, bound -> 0L));
        long sample = BackoffCalculator.jitteredDelayMillis(1);
        assertTrue(sample >= 0 && sample <= 500, "sample=" + sample);
    }
}
