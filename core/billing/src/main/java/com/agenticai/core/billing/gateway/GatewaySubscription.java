package com.agenticai.core.billing.gateway;

/**
 * Result of {@link PaymentGatewayClient#createSubscriptionWithTrial}.
 *
 * <p>{@code checkoutUrl} is the hosted page the browser should be redirected
 * to so the customer can authorize a payment method - Razorpay's
 * {@code short_url} on the created subscription. {@code status} is the
 * gateway's own initial status string (e.g. Razorpay's {@code "created"}),
 * carried through unmodified rather than translated - see
 * {@link BillingEvent}'s javadoc for the same "don't invent a parallel
 * status vocabulary" reasoning.
 */
public record GatewaySubscription(String id, String checkoutUrl, String status) {
}
