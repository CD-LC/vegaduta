package com.agenticai.core.billing.gateway;

import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;

/**
 * The two Razorpay HMAC checks: webhook deliveries (keyed by the webhook secret) and one-off
 * checkout payments (keyed by the KEY secret). Both fail closed when their secret is missing,
 * because an HMAC with a blank key is publicly computable. Split out of {@link
 * RazorpayGatewayClient}, which delegates to it.
 */
final class RazorpaySignatureVerifier {

    private final String keySecret;
    private final String webhookSecret;

    RazorpaySignatureVerifier(String keySecret, String webhookSecret) {
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
    }

    void verifyWebhookSignature(String rawBody, String signatureHeader) {
        // Fail closed, never "can't verify == accept": with no webhook secret
        // configured, HMAC(secret="") is publicly computable, so ANY posted
        // body would verify - a forged subscription.charged would flip a
        // tenant to a paid plan for free. Missing secret or missing header
        // both reject outright (401 via the controller's catch).
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new InvalidWebhookSignatureException(
                    "Razorpay webhook secret is not configured - set RAZORPAY_WEBHOOK_SECRET "
                            + "(app.billing.razorpay.webhook-secret); refusing every delivery until then");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            // The SDK NPEs on null; a header-less POST is an auth failure (401),
            // not an internal error the gateway should be told to retry.
            throw new InvalidWebhookSignatureException("Missing X-Razorpay-Signature header");
        }
        boolean valid;
        try {
            valid = Utils.verifyWebhookSignature(rawBody, signatureHeader, webhookSecret);
        } catch (RazorpayException e) {
            throw new InvalidWebhookSignatureException(
                    "Could not verify Razorpay webhook signature: " + e.getMessage());
        }
        if (!valid) {
            throw new InvalidWebhookSignatureException("Razorpay webhook signature did not match");
        }
    }

    void verifyPaymentSignature(String orderId, String paymentId, String signatureHeader) {
        // Fail closed like verifyWebhookSignature above: HMAC with a blank
        // key secret is publicly computable, so an unconfigured deployment
        // must reject rather than verify-anything.
        if (keySecret == null
                || keySecret.isBlank()
                || signatureHeader == null
                || signatureHeader.isBlank()) {
            throw new InvalidWebhookSignatureException(
                    "Razorpay payment signature cannot be verified - key secret or signature missing");
        }
        boolean valid;
        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_order_id", orderId);
            attributes.put("razorpay_payment_id", paymentId);
            attributes.put("razorpay_signature", signatureHeader);
            // HMAC over "order_id|payment_id" keyed by the KEY SECRET (not the
            // webhook secret) - Razorpay's documented one-off payment
            // verification, distinct from verifyWebhookSignature above.
            valid = Utils.verifyPaymentSignature(attributes, keySecret);
        } catch (RazorpayException e) {
            throw new InvalidWebhookSignatureException(
                    "Could not verify Razorpay payment signature: " + e.getMessage());
        }
        if (!valid) {
            throw new InvalidWebhookSignatureException("Razorpay payment signature did not match");
        }
    }
}
