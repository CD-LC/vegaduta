package com.agenticai.core.billing.gateway;

/**
 * The captured-payment facts a charge-carrying webhook carries, extracted by
 * {@link PaymentGatewayClient#extractCapturedPaymentDetails} for the generic
 * billing-webhook path's invoice recording ({@code
 * TenantSubscriptionLifecycleHandler}) - the gateway-agnostic twin of
 * {@code RazorpayGatewayClient.CapturedWebhookPayment}, which remains the
 * dedicated Razorpay webhook path's own shape.
 *
 * @param paymentId the gateway's payment/invoice identifier, recorded on the
 *     invoice row for support/reconciliation
 * @param amountMinor the charged amount in the gateway's own minor units
 *     (paise/cents), exactly what {@code InvoiceService#recordPaymentInvoice}
 *     expects - never a floating-point major amount, same money rule as
 *     {@link PaymentGatewayClient#createTopUpOrder}
 * @param currency ISO currency code as the gateway reported it
 */
public record CapturedPaymentDetails(String paymentId, long amountMinor, String currency) {
}
