package com.agenticai.core.tenancy.dto;

import java.util.UUID;

/**
 * No token/session here - logging in is still a separate step through
 * Keycloak, unchanged by the billing flow.
 *
 * <p>#970 - {@code gatewaySubscriptionId}/{@code gatewayPublicKey}/
 * {@code checkoutUrl} are always {@code null} for the signup this record
 * actually describes today: {@code POST /api/signup} only ever provisions
 * {@code TenantPlan.FREE} (there's no plan selector in {@code SignupRequest}),
 * and FREE signups no longer talk to the payment gateway at all - "no card
 * required" now means exactly that. The frontend should treat a null/absent
 * {@code gatewaySubscriptionId} as "workspace is ready, no checkout step
 * needed" rather than assuming a Razorpay Standard Checkout is always the
 * next screen. These three fields are kept (not removed) because a future
 * paid-signup path could populate them the same way a self-serve upgrade
 * does today (see {@code TenantUpgradeService} +
 * {@code Tenant.recordGatewaySubscription}'s javadoc): {@code
 * gatewaySubscriptionId}/{@code gatewayPublicKey} are what the web console
 * would need to open the payment gateway's own client-side checkout widget
 * (Razorpay's Standard Checkout - see {@code PaymentGatewayClient}'s javadoc
 * for why a bare hosted-page redirect wasn't used instead); {@code
 * gatewayPublicKey} is safe to expose to the browser - it is the gateway's
 * publishable/public identifier (Razorpay: Key ID), never its secret; and
 * {@code checkoutUrl} would be a fallback link in case the embedded widget
 * fails to load client-side.
 */
public record SignupResponse(
    UUID tenantId,
    String organizationName,
    String schemaName,
    String gatewaySubscriptionId,
    String gatewayPublicKey,
    String checkoutUrl
) {
}
