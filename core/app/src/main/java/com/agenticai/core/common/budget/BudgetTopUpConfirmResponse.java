package com.agenticai.core.common.budget;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/budget/top-up/confirm}. {@code credited} is true
 * when this call applied the top-up; {@code alreadyProcessed} is true when a
 * duplicate/replayed confirm found the order already credited (idempotent - no
 * double credit). {@code budget} is the refreshed window status so the caller
 * can immediately show the restored headroom and retry the blocked action.
 */
public record BudgetTopUpConfirmResponse(
    boolean credited,
    boolean alreadyProcessed,
    String modality,
    BigDecimal amountUsd,
    PerUserBudgetService.BudgetStatus budget
) {
}
