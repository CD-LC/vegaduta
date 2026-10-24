package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import com.agenticai.core.guardrails.ToolStepLimitExceededException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Which failures the FAST stream treats as a deliberate STOP (propagated, never recovered into a
 * normal completion) and which as a fault (recoverable). The end-to-end behaviour is pinned by
 * {@code ApprovalParkedTurnE2ETest}; this is the classification on its own, including a stop that
 * arrives wrapped.
 */
class FastStreamRecoveryPolicyStopTest {

    @Test
    void theStepCeilingAndGuardrailViolationsAreStops() {
        assertTrue(
                FastStreamRecovery.isPolicyStop(
                        new ToolStepLimitExceededException(UUID.randomUUID(), "s", 15)));
        assertTrue(
                FastStreamRecovery.isPolicyStop(new GuardrailViolationException("tool", "blocked")));
    }

    @Test
    void aStopThatArrivesWrappedIsStillAStop() {
        RuntimeException wrapped =
                new RuntimeException(
                        "tool failed",
                        new IllegalStateException(
                                "inner", new ToolStepLimitExceededException(UUID.randomUUID(), "s", 3)));

        assertTrue(FastStreamRecovery.isPolicyStop(wrapped));
    }

    @Test
    void transientFaultsAreNotStops() {
        assertFalse(FastStreamRecovery.isPolicyStop(new TimeoutException("idle")));
        assertFalse(FastStreamRecovery.isPolicyStop(new RuntimeException("boom")));
        assertFalse(FastStreamRecovery.isPolicyStop(null));
        assertFalse(
                FastStreamRecovery.isPolicyStop(
                        new UpstreamProviderException(429, "rate limited", "Try again shortly")));
    }
}
