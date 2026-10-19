package com.agenticai.core.billing.gateway;

import java.util.Set;

/**
 * A browser-safe description of one registered payment gateway, served by {@code GET /api/billing/gateways}.
 * Contains only public values - never a secret - so the web console can decide which checkout to render.
 *
 * @param name stable id ({@code "razorpay"}, {@code "stripe"} ...), the {@code {gateway}} path segment
 * @param configured whether its API credentials are present in this deployment
 * @param isDefault whether it is {@code app.billing.default-gateway}
 * @param publicKeyId the gateway's publishable key (Razorpay Key ID, Stripe publishable key); may be blank
 * @param checkoutMode widget vs hosted-page redirect
 * @param capabilities what this gateway implements
 */
public record GatewayInfo(
    String name,
    boolean configured,
    boolean isDefault,
    String publicKeyId,
    CheckoutMode checkoutMode,
    Set<GatewayCapability> capabilities
) {
}
