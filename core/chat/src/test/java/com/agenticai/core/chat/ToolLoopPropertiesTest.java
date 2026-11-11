package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code app.chat.tools.*}: the shipped defaults come from {@code /chat/tool-loop.properties} (the
 * same text as {@code application.yml}), the numeric setters clamp at zero, and {@code
 * max-rounds-per-invocation=0} means the round cap is derived from the CALL ceiling so a round cap
 * can never end a turn before the call policy does.
 */
class ToolLoopPropertiesTest {

    @Test
    void theShippedDefaultsAreTheOnesInThePropertiesFile() {
        ToolLoopProperties p = new ToolLoopProperties();

        assertEquals(0, p.getMaxRoundsPerInvocation());
        assertEquals(8, p.getMaxUnknownCallsPerInvocation());
        assertEquals(0, p.getMaxPromptTokensPerTurn());
        assertTrue(p.getBudgetExhaustedMessage().contains("{rounds}"));
        assertTrue(p.getHardStopReply().length() > 20);
        assertNotNull(p.getResultClearing());
        assertTrue(p.getResultClearing().isEnabled());
        assertEquals(
                ToolResultClearing.DEFAULT_THRESHOLD_CHARS,
                p.getResultClearing().getThresholdChars());
        assertEquals(ToolResultClearing.DEFAULT_KEEP_LAST, p.getResultClearing().getKeepLast());
        assertNotNull(p.getResultClearing().getRestoreHint());
    }

    @Test
    void anExplicitRoundCapWinsOverTheDerivedOne() {
        ToolLoopProperties p = new ToolLoopProperties();
        p.setMaxRoundsPerInvocation(3);

        assertEquals(3, p.effectiveMaxRounds(10, 5, 2));
    }

    @Test
    void theDerivedCapIsTheHighestCallCeilingPlusGracePlusOne() {
        ToolLoopProperties p = new ToolLoopProperties();

        // callsPerTurn * autonomousMultiplier + graceCalls + 1
        assertEquals(10 * 5 + 2 + 1, p.effectiveMaxRounds(10, 5, 2));
    }

    @Test
    void theDerivedCapTreatsNonPositiveInputsAsOneOrZero() {
        ToolLoopProperties p = new ToolLoopProperties();

        // Math.max(1, calls) * Math.max(1, multiplier) + Math.max(0, grace) + 1
        assertEquals(2, p.effectiveMaxRounds(0, 0, 0));
        assertEquals(2, p.effectiveMaxRounds(-4, -2, -9));
    }

    @Test
    void numericSettersClampAtZero() {
        ToolLoopProperties p = new ToolLoopProperties();
        p.setMaxRoundsPerInvocation(-3);
        p.setMaxPromptTokensPerTurn(-1);

        assertEquals(0, p.getMaxRoundsPerInvocation());
        assertEquals(0, p.getMaxPromptTokensPerTurn());
    }

    @Test
    void aNullResultClearingIsReplacedByAFreshDefault() {
        ToolLoopProperties p = new ToolLoopProperties();
        p.setResultClearing(null);

        assertNotNull(p.getResultClearing());
        assertTrue(p.getResultClearing().isEnabled());

        ToolLoopProperties.ResultClearing custom = new ToolLoopProperties.ResultClearing();
        custom.setEnabled(false);
        custom.setThresholdChars(42);
        custom.setKeepLast(7);
        custom.setRestoreHint("hint");
        p.setResultClearing(custom);
        assertFalse(p.getResultClearing().isEnabled());
        assertEquals(42, p.getResultClearing().getThresholdChars());
        assertEquals(7, p.getResultClearing().getKeepLast());
        assertEquals("hint", p.getResultClearing().getRestoreHint());
    }

    @Test
    void theTextAndCallSettersRoundTrip() {
        ToolLoopProperties p = new ToolLoopProperties();
        p.setMaxUnknownCallsPerInvocation(2);
        p.setBudgetExhaustedMessage("budget {rounds}");
        p.setHardStopReply("stop");

        assertEquals(2, p.getMaxUnknownCallsPerInvocation());
        assertEquals("budget {rounds}", p.getBudgetExhaustedMessage());
        assertEquals("stop", p.getHardStopReply());
    }
}
