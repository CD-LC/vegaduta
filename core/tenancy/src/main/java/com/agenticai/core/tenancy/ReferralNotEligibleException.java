package com.agenticai.core.tenancy;

/**
 * Referral program (PRICING-MONETIZATION-AUDIT-2026-07-31.md §8.5/D3) - thrown by
 * {@link ReferralService#redeem} when a redemption fails a rule (unknown code,
 * self-referral, already redeemed, not FREE / comp active, workspace older than
 * the new-tenant window, or the referrer's rolling-year cap is reached). Mapped
 * to 409 CONFLICT by {@code ReferralController} itself (caught there, not routed
 * through GlobalExceptionHandler - that file is outside core/tenancy), exactly
 * like {@link TrialNotEligibleException}: the request is well-formed and the
 * caller authorized, but tenant state conflicts with redeeming. The message is
 * written for end users and safe to surface verbatim.
 */
public class ReferralNotEligibleException extends RuntimeException {

    public ReferralNotEligibleException(String message) {
        super(message);
    }
}
