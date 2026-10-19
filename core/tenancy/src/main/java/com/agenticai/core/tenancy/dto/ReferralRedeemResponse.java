package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.TenantPlan;
import java.time.Instant;

/**
 * {@code POST /api/tenant/referral/redeem}'s 200 body (referral program,
 * PRICING-MONETIZATION-AUDIT-2026-07-31.md §8.5/D3). {@code granted} is always
 * {@code true} here - the ineligible case is a 409 with its own
 * {@code {granted:false, reason}} body, built by {@code ReferralController} from
 * {@code ReferralNotEligibleException}. {@code plan}/{@code expiresAt} describe
 * the REDEEMER's grant (the referrer's mirror grant is internal bookkeeping the
 * redeemer has no business reading).
 */
public record ReferralRedeemResponse(
    boolean granted,
    TenantPlan plan,
    Instant expiresAt
) {
}
