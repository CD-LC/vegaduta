package com.agenticai.core.billing.gateway;

/**
 * A parsed, gateway-agnostic webhook event, returned by
 * {@link PaymentGatewayClient#parseWebhookEvent} - only after
 * {@link PaymentGatewayClient#verifyWebhookSignature} has already confirmed
 * the raw payload is genuinely from the gateway, never before.
 *
 * @param type gateway-agnostic event classification (see {@link BillingEventType})
 * @param gatewaySubscriptionId the subscription this event is about - null
 *     only for {@link BillingEventType#UNKNOWN} events this app doesn't
 *     otherwise understand
 * @param rawEventName the original event name string exactly as the gateway
 *     sent it (e.g. Razorpay's {@code "subscription.charged"}) - kept
 *     alongside the normalized {@code type}, not discarded, so an
 *     {@code UNKNOWN}-mapped event is still diagnosable from a log line
 *     without needing to re-inspect the raw webhook body
 * @param gatewayName which gateway the verified event arrived on
 *     ({@link PaymentGatewayClient#gatewayName()}) - null only on events
 *     constructed without a gateway context (tests, synthetic events). It
 *     matters because the generic {@code /api/billing/webhook/{gateway}}
 *     endpoint feeds handlers the event detached from the path variable:
 *     the lifecycle handler stamps it onto {@code Tenant.paymentGateway}
 *     when an upgrade confirms, so a non-Razorpay upgrade doesn't leave
 *     the tenant row claiming the wrong gateway.
 * @param capturedPayment for charge-carrying events (Razorpay's {@code
 *     subscription.charged}, Stripe's {@code invoice.paid}) the captured
 *     payment's id/amount/currency, extracted once here at parse time so
 *     routed handlers downstream of the generic {@code
 *     /api/billing/webhook/{gateway}} endpoint can record an invoice
 *     without needing the raw webhook body (which the {@code
 *     RoutedBillingEventHandler} seam deliberately does not carry). Null
 *     for every event type that carries no payment.
 */
public record BillingEvent(
    BillingEventType type,
    String gatewaySubscriptionId,
    String rawEventName,
    String gatewayName,
    CapturedPaymentDetails capturedPayment
) {

    /** Pre-gatewayName constructor - the caller has no gateway context to stamp. */
    public BillingEvent(BillingEventType type, String gatewaySubscriptionId, String rawEventName) {
        this(type, gatewaySubscriptionId, rawEventName, null, null);
    }

    public BillingEvent(BillingEventType type, String gatewaySubscriptionId, String rawEventName, String gatewayName) {
        this(type, gatewaySubscriptionId, rawEventName, gatewayName, null);
    }
}
