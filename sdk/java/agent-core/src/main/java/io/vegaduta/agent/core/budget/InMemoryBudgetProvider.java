package io.vegaduta.agent.core.budget;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.budget.Budget;
import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.budget.BudgetProvider;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One {@link AtomicBudget} per run id with the same cap, kept in a bounded LRU map so a long-lived
 * process does not accumulate budgets for runs that ended.
 */
public final class InMemoryBudgetProvider implements BudgetProvider {

  private final BudgetCharge cap;
  private final Map<String, Budget> budgets;

  public InMemoryBudgetProvider(BudgetCharge cap) {
    this(cap, 10_000);
  }

  public InMemoryBudgetProvider(BudgetCharge cap, int maxRuns) {
    this.cap = cap;
    this.budgets =
        new LinkedHashMap<>(64, 0.75f, true) {
          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Budget> eldest) {
            return size() > maxRuns;
          }
        };
  }

  @Override
  public Budget budgetFor(RunContext context) {
    synchronized (budgets) {
      return budgets.computeIfAbsent(context.runId(), id -> new AtomicBudget(cap));
    }
  }

  public BudgetCharge cap() {
    return cap;
  }
}
