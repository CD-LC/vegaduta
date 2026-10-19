package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.TenantPlan;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Academy Fast-Track Scholarship one-click fulfilment - {@code POST
 * /api/tenant/comp-grant}'s request body (see CORE-COMP-GRANT-CONTRACT.md and
 * {@code CompGrantService}). Unlike {@code TenantUpgradeRequest}, this endpoint
 * is service-to-service (the Academy resolves "which tenant" itself and passes
 * {@code tenantId} explicitly, rather than core reading it from a user's
 * TenantContext), so the tenant id is part of the body here.
 *
 * <p>{@code targetPlan} must be a paid tier ({@code PREMIUM} or {@code
 * UNLIMITED}); {@code FREE} is rejected as a 400 by {@code CompGrantService}
 * (comp-granting FREE is meaningless). {@code durationMonths} must be positive.
 * {@code reason} is free-text provenance (e.g. {@code
 * "academy-fast-track-scholarship"}) - optional, stored as-is on the tenant.
 */
public record CompGrantRequest(
    @NotNull UUID tenantId,
    @NotNull TenantPlan targetPlan,
    @Positive int durationMonths,
    String reason
) {
}
