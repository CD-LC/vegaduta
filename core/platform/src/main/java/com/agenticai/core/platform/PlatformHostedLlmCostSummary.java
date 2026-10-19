package com.agenticai.core.platform;

import java.math.BigDecimal;
import java.util.List;

/**
 * #973 - "consider the price, charges to get the open source llm from open
 * router or any other platform" - the platform-wide (not per-tenant) cost of
 * running the {@code PLATFORM_HOSTED} pool (Groq/Together AI/Fireworks/etc
 * rows in {@code PlatformHostedLlmProvider}) that backs every FREE-plan
 * tenant's chat traffic. Distinct from {@link PlatformTenantLlmUsageSummary}
 * (per-tenant, blends every provider a tenant has ever used, including their
 * own BYOK keys) - this is the single number a platform-admin needs to
 * answer "what is the FREE tier actually costing US" as one figure, plus a
 * per-tenant breakdown of which FREE tenants are driving that spend.
 *
 * <p>Built the same way {@link PlatformObservabilityService#llmUsageByTenant}
 * builds its rows: one cross-schema sweep, {@code
 * LlmCallLogRepository#summarizeProvider(ProviderName.PLATFORM_HOSTED)} run
 * per tenant, summed here rather than merged/listed per-tenant. Real data
 * only - a tenant that has never used the hosted pool contributes zero, not
 * a fabricated estimate; a platform with no FREE-tier traffic yet reports
 * {@code totalCalls = 0} and {@code estimatedCostUsd = BigDecimal.ZERO}
 * honestly rather than omitting the field.
 *
 * <p>Direct feedback ("dashboard... total LLM cost incurred by the platform
 * so far... remaining balance/budget available"): {@code budgetUsd} is an
 * admin-configured all-time spending line for the hosted pool (see {@code
 * PlatformObservabilityService#updateHostedLlmBudget}, backed by {@code
 * PlatformSettingsService} - no fabricated number, {@code null} until an
 * admin actually sets one). {@code remainingBudgetUsd} is simply {@code
 * budgetUsd - estimatedCostUsd} and is {@code null} whenever {@code
 * budgetUsd} is {@code null} (an unconfigured budget has no "remaining" to
 * report) - it CAN go negative, which the UI surfaces as "over budget"
 * rather than clamping to zero.
 */
public record PlatformHostedLlmCostSummary(
    long totalCalls,
    long errorCount,
    long totalTokens,
    BigDecimal estimatedCostUsd,
    BigDecimal budgetUsd,
    BigDecimal remainingBudgetUsd,
    List<PlatformTenantHostedLlmCost> byTenant
) {

    /** One row per tenant that has ever made at least one PLATFORM_HOSTED call - zero-usage tenants are omitted, not padded in with zeros. */
    public record PlatformTenantHostedLlmCost(
        java.util.UUID tenantId, String tenantName, long totalCalls, long totalTokens, BigDecimal estimatedCostUsd
    ) {
    }
}
