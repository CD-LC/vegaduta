package com.agenticai.core.billing.gateway;

import java.util.Optional;

/**
 * PRICING-MONETIZATION-AUDIT-2026-07-31 B1 (region -&gt; Razorpay-plan binding) -
 * the two currencies this platform sells subscriptions in: INR (India PPP
 * pricing, the historical default and the only currency the pre-B1 single
 * {@code plan-id-starter}/{@code plan-id-business} config could express) and
 * USD (global pricing). Which Razorpay dashboard Plan actually gets billed for
 * a given (tier, currency, period) combination is {@link RazorpayPlanCatalog}'s
 * job - this enum is just the wire vocabulary shared between the upgrade
 * request DTO and that catalog.
 */
public enum BillingCurrency {
    INR,
    USD;

    /**
     * Lenient wire parsing for {@code POST /api/tenant/upgrade}'s optional
     * {@code currency} field: {@code null}/blank means "the caller didn't
     * ask" and defaults to {@link #INR} (pre-B1 behavior - every plan id ever
     * configured before multi-currency was an INR plan), case-insensitive
     * otherwise. Returns {@link Optional#empty()} for an unrecognized value
     * so the caller can raise its own 400-mapped exception type with a clear
     * message - this billing module deliberately doesn't know about
     * tenancy's {@code TenantUpgradeException}.
     */
    public static Optional<BillingCurrency> fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(INR);
        }
        for (BillingCurrency currency : values()) {
            if (currency.name().equalsIgnoreCase(raw.trim())) {
                return Optional.of(currency);
            }
        }
        return Optional.empty();
    }
}
