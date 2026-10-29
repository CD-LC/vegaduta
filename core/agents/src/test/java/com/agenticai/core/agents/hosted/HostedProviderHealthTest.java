package com.agenticai.core.agents.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * {@link HostedProviderHealth} is the in-memory liveness/latency memory of the hosted LLM pool.
 * These pin the consecutive-failure streak that trips a row into cooldown, the success that heals
 * it, and the latency EWMA that feeds the chain's fastest-first tie-break.
 */
class HostedProviderHealthTest {

    @Test
    void aRowIsHealthyUntilItsFailureStreakHitsTheThreshold() {
        HostedProviderHealth health = new HostedProviderHealth(3, 120, 180);

        assertFalse(health.isTripped("groq"));
        health.recordFailure("groq");
        health.recordFailure("groq");
        assertFalse(health.isTripped("groq"), "two failures is still below the threshold of 3");
        health.recordFailure("groq");
        assertTrue(health.isTripped("groq"));
        // Other rows are unaffected.
        assertFalse(health.isTripped("gemini"));
    }

    @Test
    void theThresholdOfOneTripsOnTheFirstFailure() {
        HostedProviderHealth health = new HostedProviderHealth(1, 120, 180);

        health.recordFailure("groq");

        assertTrue(health.isTripped("groq"));
    }

    @Test
    void aSuccessHealsTheRowAndClearsTheStreak() {
        HostedProviderHealth health = new HostedProviderHealth(2, 120, 180);

        health.recordFailure("groq");
        health.recordFailure("groq");
        assertTrue(health.isTripped("groq"));

        health.recordSuccess("groq", 120);
        assertFalse(health.isTripped("groq"));

        // The streak restarted from zero, so one fresh failure does not re-trip.
        health.recordFailure("groq");
        assertFalse(health.isTripped("groq"));
    }

    @Test
    void aSuccessBetweenFailuresResetsTheStreak() {
        HostedProviderHealth health = new HostedProviderHealth(2, 120, 180);

        health.recordFailure("groq");
        health.recordSuccess("groq", 50);
        health.recordFailure("groq");
        assertFalse(health.isTripped("groq"),
            "the success reset the streak, so one failure is not a streak of two");
        health.recordFailure("groq");
        assertTrue(health.isTripped("groq"));
    }

    @Test
    void latencyIsEmptyUntilTheFirstSuccessThenEWMA() {
        HostedProviderHealth health = new HostedProviderHealth(3, 120, 180);

        assertTrue(health.observedLatencyMs("groq").isEmpty());
        assertTrue(health.observedLatencyMs(null).isEmpty());

        health.recordSuccess("groq", 100);
        OptionalDouble first = health.observedLatencyMs("groq");
        assertTrue(first.isPresent());
        assertEquals(100.0, first.getAsDouble(), "the first observation seeds the EWMA");

        health.recordSuccess("groq", 200);
        // alpha=0.3: round(0.3*200 + 0.7*100) = 130.
        assertEquals(130.0, health.observedLatencyMs("groq").getAsDouble());
    }

    @Test
    void nullKeysAreIgnoredByTheWriters() {
        HostedProviderHealth health = new HostedProviderHealth(3, 120, 180);

        health.recordFailure(null);
        health.recordSuccess(null, 10);

        assertTrue(health.observedLatencyMs(null).isEmpty());
    }

    @Test
    void noRegistryMeansNoBreaking() {
        HostedProviderHealth health = new HostedProviderHealth(3, 120, 180);

        assertNull(health.acquireBreakerPermit("groq"));
        assertNull(health.acquireBreakerPermit(null));
    }
}
