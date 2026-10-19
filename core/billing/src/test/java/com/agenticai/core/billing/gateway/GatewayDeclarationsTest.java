package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Pins what each real gateway DECLARES - capabilities, checkout mode, webhook header names - because the neutral
 * checkout API, the webhook endpoint and the web console act on those declarations instead of on gateway names. A
 * gateway must only advertise what it implements end to end; these tests make an over-claim a conscious edit.
 */
class GatewayDeclarationsTest {

    private static final RazorpayGatewayClient RAZORPAY = new RazorpayGatewayClient("rzp_test_x", "secret", "whsec");

    private static final StripeGatewayClient STRIPE = new StripeGatewayClient(
        RestClient.builder(), "https://api.stripe.com", "sk_test_x", "pk_test_x", "whsec_x", "price_p", "price_u");

    @Test
    void razorpayIsAWidgetGatewayWithTheFullCapabilitySet() {
        assertEquals(CheckoutMode.WIDGET, RAZORPAY.checkoutMode());
        assertEquals("X-Razorpay-Signature", RAZORPAY.webhookSignatureHeader());
        assertEquals("X-Razorpay-Event-Id", RAZORPAY.webhookEventIdHeader());
        assertTrue(RAZORPAY.capabilities().containsAll(List.of(
            GatewayCapability.SUBSCRIPTIONS, GatewayCapability.ONE_TIME_ORDERS, GatewayCapability.PAYMENT_VERIFICATION,
            GatewayCapability.DISCOUNT_OFFERS, GatewayCapability.USAGE_ADDONS,
            GatewayCapability.UPI_COLLECT, GatewayCapability.UPI_QR)));
    }

    @Test
    void stripeIsARedirectGatewayAdvertisingOnlyWhatIsImplemented() {
        assertEquals(CheckoutMode.REDIRECT, STRIPE.checkoutMode());
        assertEquals("Stripe-Signature", STRIPE.webhookSignatureHeader());
        assertNull(STRIPE.webhookEventIdHeader(), "Stripe sends no per-delivery id header; the ledger hashes the body");
        assertTrue(STRIPE.capabilities().contains(GatewayCapability.SUBSCRIPTIONS));
        assertFalse(STRIPE.capabilities().contains(GatewayCapability.ONE_TIME_ORDERS),
            "no Stripe one-off order flow (Checkout Session + webhook crediting) exists yet");
        assertFalse(STRIPE.capabilities().contains(GatewayCapability.PAYMENT_VERIFICATION));
    }

    @Test
    void aGatewayThatDeclaresNothingIsInertByDefault() {
        PaymentGatewayClient bare = new PaymentGatewayClient() {
            @Override public String gatewayName() { return "bare"; }
            @Override public String publicKeyId() { return ""; }
            @Override public GatewaySubscription createSubscriptionWithTrial(String t, String p, int d, String e, String n) { return null; }
            @Override public void verifyWebhookSignature(String rawBody, String signatureHeader) { }
            @Override public BillingEvent parseWebhookEvent(String rawBody) { return null; }
            @Override public void createOverageAddon(String s, String d, long a, String c) { }
        };
        assertFalse(bare.isConfigured());
        assertTrue(bare.capabilities().isEmpty());
        assertEquals(CheckoutMode.REDIRECT, bare.checkoutMode());
        assertNull(bare.webhookSignatureHeader());
        assertNull(bare.webhookEventIdHeader());
    }

    @Test
    void registryDescribesGatewaysWithoutLeakingSecrets() {
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(RAZORPAY, STRIPE), "razorpay");
        List<GatewayInfo> infos = registry.describe();
        assertEquals(List.of("razorpay", "stripe"), infos.stream().map(GatewayInfo::name).toList());
        assertTrue(infos.get(0).isDefault());
        assertFalse(infos.get(1).isDefault());
        assertEquals("rzp_test_x", infos.get(0).publicKeyId());
        assertEquals("pk_test_x", infos.get(1).publicKeyId());
        // No GatewayInfo component can carry a secret; assert the record has exactly the public fields.
        assertEquals(6, GatewayInfo.class.getRecordComponents().length);
    }
}
