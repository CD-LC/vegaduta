package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.TenantPlan;

/**
 * #811 - {@code POST /api/tenant/upgrade}'s response. Same shape/reasoning
 * as {@link SignupResponse}'s three gateway fields (see that record's
 * javadoc) - the web console opens Razorpay's Standard Checkout widget with
 * {@code gatewaySubscriptionId}/{@code gatewayPublicKey}, with {@code
 * checkoutUrl} as a fallback link. {@code targetPlan} is echoed back purely
 * so the frontend's confirmation copy ("Upgrading to Premium...") doesn't
 * have to remember what it just asked for.
 *
 * <p>This response is NOT confirmation the upgrade happened - {@code
 * Tenant.plan} only actually changes once {@code RazorpayWebhookService}
 * confirms the subscription activated. Same "UX handoff only" posture
 * {@code /signup/complete} already documents for the signup flow.
 */
public record TenantUpgradeResponse(
    TenantPlan targetPlan,
    String gatewaySubscriptionId,
    String gatewayPublicKey,
    String checkoutUrl
) {
}
