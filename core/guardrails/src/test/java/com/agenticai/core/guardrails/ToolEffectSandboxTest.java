package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.guardrails.ToolEffectSandbox.Verdict;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolEffectSandbox.Verdict} is the eval sandbox's run/simulate answer: {@code run()} is one
 * shared instance, and {@code simulate} fails closed on a missing reason - a classification that
 * could not explain itself is logged as "not known to be read-only" rather than as nothing.
 */
class ToolEffectSandboxTest {

    @Test
    void runIsTheSharedExecutableVerdict() {
        assertSame(Verdict.run(), Verdict.run());
        assertTrue(Verdict.run().execute());
        assertNull(Verdict.run().reason());
    }

    @Test
    void simulateKeepsARealReason() {
        Verdict verdict = Verdict.simulate("mutates tenant data");
        assertFalse(verdict.execute());
        assertEquals("mutates tenant data", verdict.reason());
    }

    @Test
    void simulateDefaultsABlankReason() {
        assertEquals("not known to be read-only", Verdict.simulate(null).reason());
        assertEquals("not known to be read-only", Verdict.simulate("").reason());
        assertEquals("not known to be read-only", Verdict.simulate("   ").reason());
        assertFalse(Verdict.simulate(null).execute());
    }
}
