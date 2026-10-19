package io.vegaduta.agent.api.budget;

import io.vegaduta.agent.api.RunContext;

/** Finds (or creates) the {@link Budget} of a run. */
@FunctionalInterface
public interface BudgetProvider {

  Budget budgetFor(RunContext context);
}
