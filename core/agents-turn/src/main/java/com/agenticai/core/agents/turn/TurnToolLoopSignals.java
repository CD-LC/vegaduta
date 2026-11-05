package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.ToolLoopSignals;
import com.agenticai.core.guardrails.TurnApprovalState;

/**
 * The tool loop's out-of-band view of one turn: "parked" and "budget spent" are read from the
 * turn's own {@link TurnApprovalState}, which only the tool guard writes, and never from what a
 * tool printed. Wire it with {@code new
 * ToolLoopTranscript().signals(TurnToolLoopSignals.of(turnApprovalState))} wherever a turn builds
 * its transcript.
 */
public final class TurnToolLoopSignals implements ToolLoopSignals {

    private final TurnApprovalState state;

    private TurnToolLoopSignals(TurnApprovalState state) {
        this.state = state;
    }

    /**
     * The signals of the turn that owns {@code state}; {@code null} when the turn has none (the
     * loop then falls back, flagged).
     */
    public static ToolLoopSignals of(TurnApprovalState state) {
        return state == null ? null : new TurnToolLoopSignals(state);
    }

    @Override
    public boolean parked() {
        return state.isParked();
    }

    @Override
    public boolean budgetExhausted() {
        return state.isBudgetExhausted();
    }
}
