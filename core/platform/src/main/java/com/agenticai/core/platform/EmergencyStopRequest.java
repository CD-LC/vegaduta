package com.agenticai.core.platform;

/**
 * Body for both {@code POST /api/platform/kill-switch/stop} and {@code
 * POST /api/platform/tenants/{tenantId}/emergency-stop} - a free-text reason
 * for the incident-response record ({@link
 * com.agenticai.core.tenancy.TenantEmergencyControlService} defaults a
 * blank/missing reason to "No reason given" rather than rejecting the call,
 * since a platform admin hitting the kill switch during a live incident
 * should never be blocked by a missing form field).
 */
public record EmergencyStopRequest(String reason) {
}
