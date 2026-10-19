package com.agenticai.core.platform;

/**
 * Platform-admin alert threshold follow-up (#956) - {@code PUT
 * /api/platform/alerts/fallback-threshold}'s request body. Validation (must
 * be non-negative) lives in {@link
 * PlatformObservabilityService#updateFallbackFailureThreshold}, same
 * "service validates, controller just forwards" split every other write
 * endpoint in {@code PlatformController} already follows.
 */
public record UpdateFallbackAlertThresholdRequest(int threshold) {
}
