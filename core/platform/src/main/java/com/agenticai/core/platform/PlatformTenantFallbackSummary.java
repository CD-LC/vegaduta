package com.agenticai.core.platform;

import java.util.UUID;

/**
 * Platform-admin alert threshold follow-up (#956) - one row per active
 * tenant that has EVER had a fallback fire (tenants with zero of both counts
 * are omitted by {@link PlatformObservabilityService#computeFallbackAlertStatus}
 * so the breakdown list isn't padded with every quiet tenant).
 */
public record PlatformTenantFallbackSummary(
    UUID tenantId,
    String tenantName,
    long fallbackTriggeredCount,
    long fallbackFailedCount
) {
}
