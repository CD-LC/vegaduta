package com.agenticai.core.platform;

import java.util.List;

/**
 * Platform-admin alert threshold follow-up (#956) - {@code GET
 * /api/platform/alerts/fallback-status}'s response shape. {@code
 * totalFallbackFailures}/{@code totalFallbackTriggered} are LIFETIME sums
 * across every active tenant's agents (see {@code Agent.fallbackFailedCount}'s
 * own javadoc - these are cumulative counters, not a rolling window), so
 * {@code alertActive} being {@code true} means "the fleet-wide failure count
 * has crossed the configured threshold at some point and hasn't been
 * addressed" rather than "failures spiked in the last hour" - a deliberately
 * simple v1 (see {@link PlatformAlertSettings}'s javadoc for why this is one
 * threshold, not a windowed-rate engine).
 */
public record PlatformFallbackAlertStatus(
    int threshold,
    long totalFallbackTriggered,
    long totalFallbackFailures,
    boolean alertActive,
    List<PlatformTenantFallbackSummary> tenantBreakdown
) {
}
