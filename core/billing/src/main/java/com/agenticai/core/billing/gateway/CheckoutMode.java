package com.agenticai.core.billing.gateway;

/**
 * How the browser completes a payment for a gateway. The web console picks its checkout component from this,
 * never from the gateway's name.
 */
public enum CheckoutMode {
    /**
     * A client-side widget opens against an order/subscription id plus the gateway's public key
     * ({@link PaymentGatewayClient#publicKeyId()}) - Razorpay's checkout.js. The widget's success callback hands
     * back ids and a signature that the server verifies ({@link GatewayCapability#PAYMENT_VERIFICATION}).
     */
    WIDGET,
    /**
     * The browser is redirected to a hosted page URL ({@code GatewayOrder#checkoutUrl} /
     * {@code GatewaySubscription#checkoutUrl}) - Stripe Checkout, PhonePe. Completion arrives by webhook.
     */
    REDIRECT
}
