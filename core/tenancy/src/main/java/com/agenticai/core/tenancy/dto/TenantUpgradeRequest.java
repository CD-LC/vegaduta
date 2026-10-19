package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.TenantPlan;
import jakarta.validation.constraints.NotNull;

/**
 * #811 - {@code POST /api/tenant/upgrade}'s request body. {@code targetPlan} must be {@code
 * PREMIUM} or {@code UNLIMITED} - see {@code TenantUpgradeService#initiateUpgrade} for why
 * {@code FREE} is rejected.
 *
 * <p>#825 Multi-gateway follow-up - {@code gatewayName} (e.g. {@code "phonepe"}) is optional;
 * {@code null}/omitted uses {@code PaymentGatewayRegistry}'s configured default (Razorpay today).
 * Not validated against the registry's known gateway names here - an unknown name surfaces as a
 * clear {@link IllegalArgumentException} from {@code PaymentGatewayRegistry#resolve} instead,
 * mapped by {@code GlobalExceptionHandler}'s existing generic handler, same as this record's
 * sibling {@code TenantDataDeletionRequest}'s confirm-name mismatch.
 *
 * <p>PRICING-MONETIZATION-AUDIT-2026-07-31 B1/B2/D4 - three more optional fields, all deliberately
 * plain {@code String}s rather than enums so a bad value produces {@code TenantUpgradeService}'s
 * own descriptive 400 (via {@code BillingCurrency#fromWire}/{@code BillingPeriod#fromWire}) instead
 * of Jackson's generic enum-deserialization error:
 * <ul>
 *   <li>{@code currency} - {@code "INR"} | {@code "USD"}, case-insensitive; null/omitted = INR</li>
 *   <li>{@code period} - {@code "MONTHLY"} | {@code "ANNUAL"}, case-insensitive; null/omitted =
 *       MONTHLY</li>
 *   <li>{@code couponCode} - must match a configured {@code app.billing.razorpay.offers.*} code
 *       (case-insensitive); an unknown code is a 400, never silently ignored</li>
 * </ul>
 */
public record TenantUpgradeRequest(
    @NotNull TenantPlan targetPlan,
    String gatewayName,
    String currency,
    String period,
    String couponCode
) {
}
