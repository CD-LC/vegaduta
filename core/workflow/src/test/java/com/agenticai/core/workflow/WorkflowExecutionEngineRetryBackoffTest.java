package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link WorkflowExecutionEngine}'s retry backoff shape: exponential
 * (base 500ms, doubling) with a 10s cap and FULL jitter - the fix for the
 * original linear {@code 500ms * attempt} with no jitter, under which every
 * Parallel-node branch that failed a rate-limited provider at the same
 * instant retried at the same instant too. The deterministic cap progression
 * and the jitter's bounds are tested separately, so no real sleeping and no
 * seeded-random fragility; the retry-loop semantics themselves
 * (NODE_MAX_ATTEMPTS, output-node exclusion) stay covered by
 * {@link WorkflowExecutionEngineFailProofTest}.
 */
class WorkflowExecutionEngineRetryBackoffTest {

    @Test
    void backoffCapDoublesFromBaseAndIsCappedAtTenSeconds() {
        assertEquals(500, WorkflowExecutionEngine.retryBackoffCapMillis(1));
        assertEquals(1_000, WorkflowExecutionEngine.retryBackoffCapMillis(2));
        assertEquals(2_000, WorkflowExecutionEngine.retryBackoffCapMillis(3));
        assertEquals(4_000, WorkflowExecutionEngine.retryBackoffCapMillis(4));
        assertEquals(8_000, WorkflowExecutionEngine.retryBackoffCapMillis(5));
        assertEquals(10_000, WorkflowExecutionEngine.retryBackoffCapMillis(6));
        // Far past the cap: the overflow-guard exponent clamp must never
        // produce a negative/overflowed delay, just the cap.
        assertEquals(10_000, WorkflowExecutionEngine.retryBackoffCapMillis(50));
    }

    @Test
    void backoffCapTreatsNonPositiveAttemptAsFirstAttempt() {
        // Defensive: the engine only ever passes attempt >= 1, but a
        // miscounted attempt must degrade to the base delay, not a shift by
        // a negative exponent.
        assertEquals(500, WorkflowExecutionEngine.retryBackoffCapMillis(0));
        assertEquals(500, WorkflowExecutionEngine.retryBackoffCapMillis(-3));
    }

    @Test
    void jitteredDelayStaysWithinFullJitterBounds() {
        // Full jitter is uniform in [0, cap] - anything outside that range
        // on any attempt number is a regression. 200 draws per attempt keeps
        // this fast while making an off-by-one bound bug (e.g. exclusive
        // upper bound arithmetic gone wrong) very likely to surface.
        for (int attempt = 1; attempt <= 8; attempt++) {
            long cap = WorkflowExecutionEngine.retryBackoffCapMillis(attempt);
            for (int i = 0; i < 200; i++) {
                long delay = WorkflowExecutionEngine.jitteredRetryDelayMillis(attempt);
                assertTrue(delay >= 0 && delay <= cap,
                    "attempt " + attempt + ": delay " + delay + " outside [0, " + cap + "]");
            }
        }
    }
}
