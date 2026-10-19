package com.agenticai.core.agents.appbuilder;

import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.sdlc.harness.HarnessBudget;

/** Adapts the per-run {@link RunBudget} to the coding harness's {@link HarnessBudget}. */
public final class RunBudgetHarnessBudget implements HarnessBudget {

    private final RunBudget budget;

    public RunBudgetHarnessBudget(RunBudget budget) {
        this.budget = budget;
    }

    @Override
    public boolean exhausted() {
        return budget != null && budget.isExceeded();
    }

    @Override
    public void record(String input, String output) {
        if (budget != null) {
            budget.record(input, output);
        }
    }

    @Override
    public String stopMessage() {
        return budget == null ? "" : budget.stopMessage();
    }
}
