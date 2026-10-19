package com.agenticai.core.platform;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of {@code GET /api/platform/llm-usage} - all-time LLM call
 * volume/cost for one tenant, the platform-wide counterpart to the tenant
 * console's own "LLM usage &amp; cost" panel ({@code LlmCallLogRepository}).
 * All-time, not a rolling window (unlike {@code SystemStatusResponse}'s 24h
 * telemetry strip) - this is a per-tenant billing/usage rollup, not a live
 * dashboard tile, so the whole history is the relevant number here.
 *
 * @param avgLatencyMs {@code null} when {@code totalCalls} is 0 - see {@code
 *     LlmOverallUsageSummary}'s javadoc for why this is never fabricated as
 *     {@code 0.0}.
 */
public record PlatformTenantLlmUsageSummary(
    UUID tenantId,
    String tenantName,
    long totalCalls,
    long errorCount,
    Double avgLatencyMs,
    long totalTokens,
    BigDecimal estimatedCostUsd
) {
}
