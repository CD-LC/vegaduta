package com.agenticai.core.billing.gateway;

/**
 * What a {@link PaymentGatewayClient} can actually do in this codebase. Callers (the checkout API, the web
 * console) ask {@link PaymentGatewayClient#capabilities()} instead of testing for a gateway by name, so a new
 * gateway (Stripe, PhonePe, Cashfree ...) lights up exactly the surfaces it implements and nothing else - the
 * rest answer a clear "not supported by this gateway" rather than a silent wrong charge.
 *
 * <p>Each value maps to one interface method (or group); a gateway must only advertise a capability whose
 * method it overrides end to end, including how the payment is confirmed (webhook or signature check).
 */
public enum GatewayCapability {
    /** {@link PaymentGatewayClient#createSubscriptionWithTrial} - recurring billing with a trial. */
    SUBSCRIPTIONS,
    /** {@link PaymentGatewayClient#createTopUpOrder} - a single one-off charge (top-up). */
    ONE_TIME_ORDERS,
    /** {@link PaymentGatewayClient#verifyPaymentSignature} - the browser-returned payment can be verified server-side. */
    PAYMENT_VERIFICATION,
    /** The offer-id overload of {@code createSubscriptionWithTrial} - gateway-side discounts/coupons. */
    DISCOUNT_OFFERS,
    /** {@link PaymentGatewayClient#createOverageAddon} - usage overage billed on the next invoice. */
    USAGE_ADDONS,
    /** {@link PaymentGatewayClient#createUpiCollectRequest} - UPI intent payment links (India). */
    UPI_COLLECT,
    /** {@link PaymentGatewayClient#createUpiQrCode} - scannable UPI QR codes (India). */
    UPI_QR
}
