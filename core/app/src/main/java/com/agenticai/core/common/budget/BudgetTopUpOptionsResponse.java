package com.agenticai.core.common.budget;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body of {@code GET /api/budget/top-up/options} - the purchasable amounts plus
 * the gateway details the browser needs to open checkout (gateway name + public
 * key id). {@code enabled} lets the UI hide the top-up affordance entirely when
 * the platform has it switched off.
 */
public record BudgetTopUpOptionsResponse(
    boolean enabled,
    String currency,
    BigDecimal minUsd,
    BigDecimal maxUsd,
    String gatewayName,
    String publicKeyId,
    List<BudgetTopUpOption> options
) {
}
