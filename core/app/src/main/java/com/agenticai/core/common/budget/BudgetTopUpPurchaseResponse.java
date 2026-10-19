package com.agenticai.core.common.budget;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/budget/top-up} - everything the browser needs to
 * open the gateway's client-side checkout widget against the newly-created
 * one-off order ({@code orderId} + {@code publicKeyId}), then post the result
 * back to {@code POST /api/budget/top-up/confirm}. Nothing is credited yet;
 * this is the "order created, awaiting payment" step.
 */
public record BudgetTopUpPurchaseResponse(
    String gatewayName,
    String publicKeyId,
    String orderId,
    long amountInSubunits,
    String currency,
    String modality,
    BigDecimal amountUsd,
    String status
) {
}
