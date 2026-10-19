package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.UUID;

/**
 * Academy Fast-Track Scholarship one-click fulfilment - {@code POST
 * /api/tenant/comp-grant}'s 200 response (see CORE-COMP-GRANT-CONTRACT.md).
 * {@code compGrantedAt}/{@code compExpiresAt} serialize as ISO-8601 instants;
 * the Academy records these against the fulfilled claim.
 */
public record CompGrantResponse(
    UUID tenantId,
    TenantPlan plan,
    Instant compGrantedAt,
    Instant compExpiresAt,
    String reason
) {
}
