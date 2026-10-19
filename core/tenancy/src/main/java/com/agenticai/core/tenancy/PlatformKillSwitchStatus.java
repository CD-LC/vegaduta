package com.agenticai.core.tenancy;

import java.time.Instant;

/**
 * #815 Governance follow-up (Fail-safe: platform + per-tenant kill switch) -
 * read shape for {@link TenantEmergencyControlService#getStatus()}, returned
 * directly by {@code PlatformController}'s {@code GET /api/platform/kill-switch}
 * (Jackson serializes it fine from {@code core/platform} even though the
 * record itself lives here - no DTO duplication needed, same "just return
 * the domain-level read model" posture {@code PlatformController} already
 * uses for {@code PlatformTenantSummary}).
 */
public record PlatformKillSwitchStatus(boolean stopped, String reason, Instant stoppedAt, String updatedBy) {
}
