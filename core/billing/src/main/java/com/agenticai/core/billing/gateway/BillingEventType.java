package com.agenticai.core.billing.gateway;

/**
 * Normalized subscription lifecycle event, one gateway-agnostic vocabulary
 * every {@link PaymentGatewayClient} implementation maps its own raw webhook
 * event names into (see {@link BillingEvent}) - Stage 4's webhook handler
 * code depends only on this enum, never on Razorpay's (or, later, Stripe's)
 * raw event name strings directly.
 *
 * <p>Named after Razorpay's actual {@code subscription.*} event set (see
 * razorpay.com/docs/webhooks/subscriptions), since that's the gateway this
 * app integrates first - a future second gateway implementation maps its
 * own differently-named events onto this same set rather than this enum
 * growing a second, gateway-specific vocabulary.
 *
 * <p>{@code SUBSCRIPTION_AUTHENTICATED} is fired for BOTH card e-mandates
 * and UPI Autopay mandate registration - Razorpay does not document a
 * separate event for "UPI mandate specifically" (confirmed against
 * razorpay.com/docs/webhooks/subscriptions during the #811-UPI follow-up,
 * VEGADUTA-MASTER-ROADMAP-2026-07-19.md §9.2/§10.2) - the payment method
 * actually used only shows up inside the nested payment entity on whichever
 * event carries one ({@code subscription.charged} etc.), not as a distinct
 * top-level event type. {@code SUBSCRIPTION_UPDATED} (added same pass) was
 * previously undocumented in this enum and silently fell through to {@link
 * #UNKNOWN} - a real, independent gap found while cross-checking the full
 * event list, not a UPI-specific fix.
 */
public enum BillingEventType {
    SUBSCRIPTION_AUTHENTICATED,
    SUBSCRIPTION_ACTIVATED,
    SUBSCRIPTION_CHARGED,
    SUBSCRIPTION_PENDING,
    SUBSCRIPTION_HALTED,
    SUBSCRIPTION_PAUSED,
    SUBSCRIPTION_RESUMED,
    SUBSCRIPTION_CANCELLED,
    SUBSCRIPTION_COMPLETED,
    SUBSCRIPTION_UPDATED,
    /**
     * UPI-collect-request follow-up: a Razorpay UPI Payment Link ({@link
     * PaymentGatewayClient#createUpiCollectRequest}) was paid (Razorpay's
     * {@code payment_link.paid}). Handled by {@code
     * UpiPaymentConfirmedHandler} - crediting a UPI-collect payment is
     * inherently async (no synchronous checkout-widget callback the way
     * {@link RazorpayCheckoutController#verifyPayment} handles Standard
     * Checkout) and this app has no tenant/user/modality attribution for a
     * UPI Payment Link/QR code today, so that handler only durably logs the
     * confirmation - see its javadoc for the full "why not credit anything"
     * reasoning.
     */
    PAYMENT_LINK_PAID,
    /**
     * UPI-collect-request follow-up: a Razorpay UPI QR code ({@link
     * PaymentGatewayClient#createUpiQrCode}) was scanned and paid
     * (Razorpay's {@code qr_code.credited}). Same handler/caveat as
     * {@link #PAYMENT_LINK_PAID}.
     */
    QR_CODE_PAYMENT_RECEIVED,
    /** A real event the gateway sent that this app doesn't (yet) have a mapping for - logged, not dropped silently. */
    UNKNOWN
}
