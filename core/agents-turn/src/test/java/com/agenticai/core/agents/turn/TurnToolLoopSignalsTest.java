package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.chat.ToolLoopSignals;
import com.agenticai.core.guardrails.TurnApprovalState;
import org.junit.jupiter.api.Test;

class TurnToolLoopSignalsTest {

    @Test
    void readsTheTurnsOwnStateAndNothingElse() {
        TurnApprovalState mine = new TurnApprovalState(null);
        TurnApprovalState theirs = new TurnApprovalState(null);
        ToolLoopSignals signals = TurnToolLoopSignals.of(mine);

        assertNotNull(signals);
        assertFalse(signals.parked());
        assertFalse(signals.budgetExhausted());

        mine.markBudgetExhausted();
        assertTrue(signals.budgetExhausted());
        assertFalse(TurnToolLoopSignals.of(theirs).budgetExhausted(), "a concurrent turn is unaffected");
    }

    @Test
    void aTurnWithoutStateHasNoSignals() {
        assertNull(TurnToolLoopSignals.of(null), "the loop then uses its flagged content fallback");
    }
}
