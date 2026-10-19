package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.TenantPlan;
import java.time.Instant;

/**
 * {@code POST /api/tenant/trial/claim}'s 200 body (event-triggered 7-day
 * Premium trial, PRICING-MONETIZATION-AUDIT-2026-07-31.md §5.4/B6).
 * {@code granted} is always {@code true} here - the ineligible case is a 409
 * with its own {@code {granted:false, reason}} body, built by {@code
 * TrialGrantController} from {@code TrialNotEligibleException}.
 */
public record TrialClaimResponse(
    boolean granted,
    TenantPlan plan,
    Instant expiresAt
) {
}
