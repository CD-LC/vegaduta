package com.agenticai.core.platform;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One calendar day's {@code PLATFORM_HOSTED}-only totals, summed across every
 * active tenant - the trend line for {@link PlatformHostedLlmCostSummary}'s
 * dashboard (direct feedback: "total LLM cost incurred by the platform so
 * far... broken down by day/week or at least a running total"). Built by
 * {@link PlatformObservabilityService#platformHostedLlmCostByDay}, one row
 * per day that had at least one PLATFORM_HOSTED call across any tenant - a
 * day with zero hosted-pool traffic anywhere is simply absent from the list,
 * not padded in with a zero row.
 */
public record PlatformHostedLlmCostDay(
    LocalDate date,
    long totalCalls,
    long totalTokens,
    BigDecimal estimatedCostUsd
) {
}
