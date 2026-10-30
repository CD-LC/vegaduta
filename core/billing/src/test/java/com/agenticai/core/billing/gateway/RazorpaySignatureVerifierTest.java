package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The fail-closed HMAC checks behind {@code RazorpayGatewayClient}: a blank webhook or key secret
 * rejects everything rather than verifying with a publicly computable key, a missing signature
 * header is an auth failure not an NPE, and genuinely correct HMACs (computed here with plain JCA,
 * the same algorithm Razorpay documents) pass.
 */
class RazorpaySignatureVerifierTest {

    private static final String KEY_SECRET = "key-secret-abc";
    private static final String WEBHOOK_SECRET = "webhook-secret-xyz";

    private static String hmacSha256Hex(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void webhookFailsClosedWhenTheSecretIsMissing() {
        assertThrows(
                InvalidWebhookSignatureException.class,
                () ->
                        new RazorpaySignatureVerifier(KEY_SECRET, null)
                                .verifyWebhookSignature("{}", "sig"));
        assertThrows(
                InvalidWebhookSignatureException.class,
                () ->
                        new RazorpaySignatureVerifier(KEY_SECRET, "  ")
                                .verifyWebhookSignature("{}", "sig"));
    }

    @Test
    void webhookRejectsAMissingSignatureHeader() {
        RazorpaySignatureVerifier verifier =
                new RazorpaySignatureVerifier(KEY_SECRET, WEBHOOK_SECRET);
        assertThrows(
                InvalidWebhookSignatureException.class,
                () -> verifier.verifyWebhookSignature("{}", null));
        assertThrows(
                InvalidWebhookSignatureException.class,
                () -> verifier.verifyWebhookSignature("{}", "  "));
    }

    @Test
    void webhookAcceptsACorrectHmacAndRejectsAWrongOne() throws Exception {
        RazorpaySignatureVerifier verifier =
                new RazorpaySignatureVerifier(KEY_SECRET, WEBHOOK_SECRET);
        String body = "{\"event\":\"subscription.charged\"}";
        String good = hmacSha256Hex(body, WEBHOOK_SECRET);
        assertDoesNotThrow(() -> verifier.verifyWebhookSignature(body, good));
        InvalidWebhookSignatureException ex =
                assertThrows(
                        InvalidWebhookSignatureException.class,
                        () ->
                                verifier.verifyWebhookSignature(
                                        body, hmacSha256Hex(body, "wrong-secret")));
        assertTrue(ex.getMessage().contains("did not match"));
    }

    @Test
    void webhookRejectsATamperedBody() throws Exception {
        RazorpaySignatureVerifier verifier =
                new RazorpaySignatureVerifier(KEY_SECRET, WEBHOOK_SECRET);
        String good = hmacSha256Hex("{\"event\":\"subscription.charged\"}", WEBHOOK_SECRET);
        assertThrows(
                InvalidWebhookSignatureException.class,
                () ->
                        verifier.verifyWebhookSignature(
                                "{\"event\":\"subscription.cancelled\"}", good));
    }

    @Test
    void paymentSignatureFailsClosedWithoutKeySecretOrSignature() {
        assertThrows(
                InvalidWebhookSignatureException.class,
                () ->
                        new RazorpaySignatureVerifier(null, WEBHOOK_SECRET)
                                .verifyPaymentSignature("order_1", "pay_1", "sig"));
        assertThrows(
                InvalidWebhookSignatureException.class,
                () ->
                        new RazorpaySignatureVerifier(KEY_SECRET, WEBHOOK_SECRET)
                                .verifyPaymentSignature("order_1", "pay_1", null));
        assertThrows(
                InvalidWebhookSignatureException.class,
                () ->
                        new RazorpaySignatureVerifier(KEY_SECRET, WEBHOOK_SECRET)
                                .verifyPaymentSignature("order_1", "pay_1", " "));
    }

    @Test
    void paymentSignatureAcceptsTheOrderPipePaymentHmac() throws Exception {
        // Razorpay's documented one-off verification: HMAC-SHA256 over
        // "order_id|payment_id" keyed by the KEY secret (not the webhook secret).
        String signature = hmacSha256Hex("order_1|pay_1", KEY_SECRET);
        RazorpaySignatureVerifier verifier =
                new RazorpaySignatureVerifier(KEY_SECRET, WEBHOOK_SECRET);
        assertDoesNotThrow(() -> verifier.verifyPaymentSignature("order_1", "pay_1", signature));
    }

    @Test
    void paymentSignatureRejectsAWrongHmac() throws Exception {
        RazorpaySignatureVerifier verifier =
                new RazorpaySignatureVerifier(KEY_SECRET, WEBHOOK_SECRET);
        String wrong = hmacSha256Hex("order_1|pay_1", "someone-elses-secret");
        assertThrows(
                InvalidWebhookSignatureException.class,
                () -> verifier.verifyPaymentSignature("order_1", "pay_1", wrong));
        // The webhook secret must not validate a payment signature - different key.
        String wrongKey = hmacSha256Hex("order_1|pay_1", WEBHOOK_SECRET);
        assertThrows(
                InvalidWebhookSignatureException.class,
                () -> verifier.verifyPaymentSignature("order_1", "pay_1", wrongKey));
    }
}
