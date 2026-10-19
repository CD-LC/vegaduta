package com.agenticai.core.billing.gateway;

import java.util.Optional;

/**
 * PRICING-MONETIZATION-AUDIT-2026-07-31 B2 (annual plans, 2-months-free
 * framing) - the two billing cadences a subscription can be created on. As
 * with {@link BillingCurrency}, the actual price difference lives entirely in
 * which Razorpay dashboard Plan {@link RazorpayPlanCatalog} resolves - an
 * "annual" plan is just a Razorpay Plan with a yearly interval and its own
 * amount, nothing period-specific exists in this app's charging code.
 */
public enum BillingPeriod {
    MONTHLY,
    ANNUAL;

    /**
     * Lenient wire parsing for {@code POST /api/tenant/upgrade}'s optional
     * {@code period} field: {@code null}/blank defaults to {@link #MONTHLY}
     * (pre-B2 behavior), case-insensitive otherwise; {@link Optional#empty()}
     * for an unrecognized value - same contract and reasoning as
     * {@link BillingCurrency#fromWire}.
     */
    public static Optional<BillingPeriod> fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(MONTHLY);
        }
        for (BillingPeriod period : values()) {
            if (period.name().equalsIgnoreCase(raw.trim())) {
                return Optional.of(period);
            }
        }
        return Optional.empty();
    }
}
