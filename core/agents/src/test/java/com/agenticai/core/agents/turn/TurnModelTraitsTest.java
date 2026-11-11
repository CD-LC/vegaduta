package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TurnModelTraits} carries what client selection learned about the model one turn runs on,
 * shared by reference through the stages. These pin the defaults (a model is assumed able and its
 * tool set per-message until told otherwise) and that the two flags move independently.
 */
class TurnModelTraitsTest {

    @Test
    void aFreshTurnAssumesACapableModelWithAPerMessageToolSet() {
        TurnModelTraits traits = new TurnModelTraits();

        assertFalse(traits.weakToolCaller());
        assertFalse(traits.stableToolSet());
    }

    @Test
    void eachFlagMovesOnItsOwn() {
        TurnModelTraits traits = new TurnModelTraits();

        traits.weakToolCaller(true);
        assertTrue(traits.weakToolCaller());
        assertFalse(traits.stableToolSet());

        traits.stableToolSet(true);
        assertTrue(traits.weakToolCaller());
        assertTrue(traits.stableToolSet());

        traits.weakToolCaller(false);
        assertFalse(traits.weakToolCaller());
        assertTrue(traits.stableToolSet());
    }
}
