package com.agenticai.core.billing.gateway;

/**
 * Result of {@link PaymentGatewayClient#createTopUpOrder} - a one-off payment
 * order (Razorpay: an Order), distinct from the recurring
 * {@link GatewaySubscription} the plan-upgrade flow uses. This is the shape a
 * per-user budget top-up (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 P2)
 * needs: a single charge, not a mandate.
 *
 * <p>{@code orderId} is what the browser-side checkout widget opens against
 * (Razorpay: {@code order_id}, alongside the gateway's public key id).
 * {@code amountInSubunits}/{@code currency} echo back exactly what the order
 * was created for (smallest currency unit - paise/cents - never a
 * floating-point major amount, same money-handling rule the rest of this
 * package follows). {@code checkoutUrl} is set only by hosted-page gateways
 * ({@link CheckoutMode#REDIRECT}) and is {@code null} for widget gateways. {@code status} is the gateway's own initial status string
 * (Razorpay: {@code "created"}), carried through unmodified for the same
 * "don't invent a parallel status vocabulary" reason as {@link BillingEvent}.
 */
public record GatewayOrder(String orderId, String status, long amountInSubunits, String currency, String checkoutUrl) {

    /** Widget-style gateways (Razorpay) have no hosted page: the browser opens its SDK against {@code orderId}. */
    public GatewayOrder(String orderId, String status, long amountInSubunits, String currency) {
        this(orderId, status, amountInSubunits, currency, null);
    }
}
