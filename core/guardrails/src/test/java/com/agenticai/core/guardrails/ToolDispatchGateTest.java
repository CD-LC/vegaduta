package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.guardrails.ToolDispatchGate.Outcome;
import com.agenticai.core.guardrails.ToolDispatchGate.Verdict;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolDispatchGate.Verdict} normalises itself at construction: a null outcome reads as ALLOW
 * (a gate that answers nothing never silently denies), and the {@code readOnly} flag only ever
 * survives on an ALLOW - a refusal or an approval requirement is never "safe to run unattended"
 * whatever the caller claimed.
 */
class ToolDispatchGateTest {

    @Test
    void aNullOutcomeNormalisesToAllow() {
        Verdict verdict = new Verdict(null, "gate answered nothing");
        assertEquals(Outcome.ALLOW, verdict.outcome());
        assertEquals("gate answered nothing", verdict.reason());
    }

    @Test
    void readOnlySurvivesOnlyOnAllow() {
        assertTrue(new Verdict(Outcome.ALLOW, null, true).readOnly());
        assertFalse(
                new Verdict(Outcome.DENY, "no", true).readOnly(),
                "a denial can never be marked read-only");
        assertFalse(
                new Verdict(Outcome.REQUIRE_APPROVAL, "needs a human", true).readOnly(),
                "an approval requirement can never be marked read-only");
    }

    @Test
    void allowIsTheSharedInstance() {
        assertSame(Verdict.allow(), Verdict.allow());
        assertEquals(Outcome.ALLOW, Verdict.allow().outcome());
        assertNull(Verdict.allow().reason());
        assertFalse(Verdict.allow().readOnly());
    }

    @Test
    void allowReadOnlyMarksTheAllowAsVouched() {
        Verdict verdict = Verdict.allowReadOnly();
        assertEquals(Outcome.ALLOW, verdict.outcome());
        assertTrue(verdict.readOnly());
    }

    @Test
    void denyAndRequireApprovalCarryTheirReasons() {
        Verdict denied = Verdict.deny("policy says no");
        assertEquals(Outcome.DENY, denied.outcome());
        assertEquals("policy says no", denied.reason());
        assertFalse(denied.readOnly());

        Verdict held = Verdict.requireApproval("consequential action");
        assertEquals(Outcome.REQUIRE_APPROVAL, held.outcome());
        assertEquals("consequential action", held.reason());
    }

    @Test
    void theTwoArgumentConstructorIsAnOrdinaryNonReadOnlyVerdict() {
        Verdict verdict = new Verdict(Outcome.DENY, "blocked");
        assertEquals(Outcome.DENY, verdict.outcome());
        assertEquals("blocked", verdict.reason());
        assertFalse(verdict.readOnly());
    }
}
