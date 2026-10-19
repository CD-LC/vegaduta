package com.agenticai.core.tenancy.dto;

/**
 * {@code GET /api/tenant/referral}'s body (referral program,
 * PRICING-MONETIZATION-AUDIT-2026-07-31.md §8.5/D3).
 *
 * <p>{@code code} is this tenant's own shareable referral code - lazily
 * generated and persisted on the first read, stable forever after.
 * {@code redemptionsCount} is the all-time number of tenants that redeemed it.
 * {@code redeemedByMe} - has THIS tenant already spent its one redemption of
 * someone else's code. {@code canRedeem} - would a redemption attempt pass the
 * redeemer-side eligibility rules right now (FREE plan, no active comp, within
 * the new-tenant window, never redeemed) - the web app reads this to decide
 * whether to show the "have a code?" input at all.
 */
public record ReferralStatusResponse(
    String code,
    long redemptionsCount,
    boolean redeemedByMe,
    boolean canRedeem
) {
}
