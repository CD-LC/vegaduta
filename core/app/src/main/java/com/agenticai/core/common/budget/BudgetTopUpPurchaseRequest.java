package com.agenticai.core.common.budget;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/budget/top-up} - which modality's budget to top up
 * and by how much (USD). {@code modality} is optional and defaults to
 * {@code text}; {@code amountUsd} must be one within the configured min/max
 * (validated server-side in {@code BudgetTopUpService}).
 */
public record BudgetTopUpPurchaseRequest(String modality, BigDecimal amountUsd) {
}
