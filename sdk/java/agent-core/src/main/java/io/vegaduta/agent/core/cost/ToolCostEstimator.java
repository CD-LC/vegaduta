package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.risk.ToolClassification;
import java.math.BigDecimal;

/**
 * Tells the pipeline what to reserve from the run's budget before a tool call. The pipeline
 * reserves the estimate, runs the tool and commits the estimate; a budget therefore refuses a call
 * whose estimate does not fit, before any side effect happens.
 */
@Experimental
@FunctionalInterface
public interface ToolCostEstimator {

  /** The charge to reserve for one call of the classified tool. Usually one tool call plus USD. */
  BudgetCharge estimateFor(ToolClassification classification);

  /** The same constant for every tool, which is what the SDK did before cost models existed. */
  static ToolCostEstimator constant(BigDecimal usdPerToolCall) {
    BudgetCharge charge = new BudgetCharge(1, 0, usdPerToolCall);
    return classification -> charge;
  }
}
