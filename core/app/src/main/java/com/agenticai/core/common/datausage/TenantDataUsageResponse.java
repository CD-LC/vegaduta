package com.agenticai.core.common.datausage;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/tenant/data-usage} - a tenant-admin's own, honest "how does
 * our infrastructure use your data" transparency view (see {@link
 * TenantDataUsageService}'s javadoc for the per-system reasoning). {@code
 * tenantId} is the tenant's real UUID primary key ({@code
 * Tenant#getId()}) - the same field {@code TenantDataExportService}'s export
 * payload already uses for this key, not the schema name {@link
 * com.agenticai.core.security.TenantContext#getTenantId()} actually returns.
 */
public record TenantDataUsageResponse(
    Instant generatedAt,
    String tenantId,
    List<SystemUsage> systems
) {

    /**
     * One backend system's entry. {@code liveStat} is {@code null} whenever a
     * live number either isn't meaningful to show standalone (Redis, Temporal,
     * Keycloak - see {@link TenantDataUsageService}) or a live read genuinely
     * failed (degrades to {@code null}, never a fabricated value - same
     * no-fabricated-data rule {@code SystemStatusController} already
     * establishes for the adjacent System Status panel). {@code status} is
     * {@code "active"} for every system actually in the tenant's live request
     * path today, and {@code "available_not_engaged"} for the one system
     * that, right now, honestly is not (Temporal - see that entry's own
     * comment in {@link TenantDataUsageService}).
     */
    public record SystemUsage(
        String id,
        String name,
        String vendor,
        String role,
        String description,
        String isolationFact,
        LiveStat liveStat,
        String status
    ) {
    }

    /** {@code value} is a plain number or string - Jackson serializes either as-is, no wrapper type needed. */
    public record LiveStat(String label, Object value) {
    }
}
