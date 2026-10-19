package com.agenticai.core.platform;

import java.math.BigDecimal;

/**
 * {@code PUT /api/platform/hosted-llm-cost/budget} body - sets (or clears,
 * when {@code budgetUsd} is {@code null}) the admin-configured all-time
 * spending line for the {@code PLATFORM_HOSTED} pool. See {@link
 * PlatformHostedLlmCostSummary}'s javadoc for what this feeds into.
 */
public record PlatformHostedLlmBudgetUpdateRequest(BigDecimal budgetUsd) {
}
