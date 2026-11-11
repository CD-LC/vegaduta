package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PromptProfileTest {

    @Test
    void parsesAnyCaseAndFallsBackToFull() {
        assertEquals(PromptProfile.BARE, PromptProfile.parse("bare"));
        assertEquals(PromptProfile.LEAN, PromptProfile.parse(" Lean "));
        assertEquals(PromptProfile.FULL, PromptProfile.parse("FULL"));
        assertEquals(PromptProfile.FULL, PromptProfile.parse(null));
        assertEquals(PromptProfile.FULL, PromptProfile.parse(""));
        assertEquals(PromptProfile.FULL, PromptProfile.parse("minimal"));
    }

    @Test
    void aTurnStartsFullAndNeverHoldsNull() {
        TurnModelTraits traits = new TurnModelTraits();
        assertEquals(PromptProfile.FULL, traits.promptProfile());
        traits.promptProfile(PromptProfile.BARE);
        assertEquals(PromptProfile.BARE, traits.promptProfile());
        traits.promptProfile(null);
        assertEquals(PromptProfile.FULL, traits.promptProfile());
        assertEquals("lean", PromptProfile.LEAN.json());
    }
}
