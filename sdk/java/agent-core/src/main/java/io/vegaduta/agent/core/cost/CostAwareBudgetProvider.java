package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.budget.Budget;
import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.budget.BudgetProvider;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.budget.InMemoryBudgetProvider;
import java.util.Objects;

/**
 * A {@link BudgetProvider} that also knows what each tool costs. Budgets come from the wrapped
 * provider (by default an {@link InMemoryBudgetProvider}); estimates come from the {@link
 * CostModel}, so a run's USD cap is enforced against real per-tool prices rather than one constant.
 *
 * <pre>{@code
 * CostAwareBudgetProvider budgets = CostAwareBudgetProvider.inMemory(costModel,
 *     new BudgetCharge(50, 0, new BigDecimal("1.00")));
 * Governance governance = Governance.builder().budgets(budgets).build();
 * }</pre>
 */
@Experimental
public final class CostAwareBudgetProvider implements BudgetProvider, ToolCostEstimator {

  private final BudgetProvider delegate;
  private final CostModel model;

  public CostAwareBudgetProvider(BudgetProvider delegate, CostModel model) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.model = Objects.requireNonNull(model, "model");
  }

  /** Wraps a fresh {@link InMemoryBudgetProvider} with the given per-run cap. */
  public static CostAwareBudgetProvider inMemory(CostModel model, BudgetCharge cap) {
    return new CostAwareBudgetProvider(new InMemoryBudgetProvider(cap), model);
  }

  public BudgetProvider delegate() {
    return delegate;
  }

  public CostModel model() {
    return model;
  }

  @Override
  public Budget budgetFor(RunContext context) {
    return delegate.budgetFor(context);
  }

  /** One tool call plus the model's USD estimate for the classified tool. */
  @Override
  public BudgetCharge estimateFor(ToolClassification classification) {
    return estimateFor(classification.toolName());
  }

  /** One tool call plus the model's USD estimate for {@code toolName}. */
  public BudgetCharge estimateFor(String toolName) {
    return new BudgetCharge(1, 0, model.toolCost(toolName));
  }
}
