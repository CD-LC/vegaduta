package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Covers the deterministic, no-network-call logic of {@link StripeGatewayClient} - the same
 * scoping precedent {@link RazorpayGatewayClientTest} and {@link PhonePeGatewayClientTest}
 * already established. The {@code RestClient} POST/DELETE bodies
 * ({@link StripeGatewayClient#createSubscriptionWithTrial}'s HTTP legs, {@link
 * StripeGatewayClient#createOverageAddon}, {@link StripeGatewayClient#cancelSubscription})
 * are NOT exercised here; what IS covered:
 *
 * <ul>
 *   <li>{@link StripeGatewayClient#verifyWebhookSignature} - Stripe's {@code
 *       "t=<unix>,v1=<hexHmac>"} scheme over the literal payload {@code "<t>.<rawBody>"}, a
 *       real security boundary: valid signature accepted, wrong/missing/malformed signature
 *       rejected, timestamp outside the 300 s tolerance rejected (replay), and an empty
 *       webhook secret fails closed.
 *   <li>{@link StripeGatewayClient#parseWebhookEvent} - JSON-to-{@link BillingEvent} mapping:
 *       subscription id from both the {@code data.object.id} (customer.subscription.*) and
 *       the invoice {@code parent.subscription_details.subscription} / legacy {@code
 *       subscription} shapes, captured-payment extraction on {@code invoice.paid}, and the
 *       deliberate default-to-{@link BillingEventType#UNKNOWN} for unrecognised event names
 *       and unparseable bodies.
 *   <li>{@link StripeGatewayClient#resolvePriceId} via {@code createSubscriptionWithTrial} -
 *       the plan-name to Price-id resolution throws a {@link PaymentGatewayException} naming
 *       the missing configuration property before any HTTP call, so it is reachable without
 *       a network double.
 *   <li>{@link StripeGatewayClient#isConfigured} and the unconfigured fail-fast on
 *       subscription creation, overage and cancellation.
 * </ul>
 */
class StripeGatewayClientTest {

    private static final String API_BASE_URL = "https://api.stripe.com";
    private static final String SECRET_KEY = "sk_test_dummy";
    private static final String PUBLISHABLE_KEY = "pk_test_dummy";
    private static final String WEBHOOK_SECRET = "whsec_test_dummy";
    private static final String PRICE_PREMIUM = "price_premium_dummy";
    private static final String PRICE_UNLIMITED = "price_unlimited_dummy";

    private StripeGatewayClient client(String secretKey, String webhookSecret,
        String priceIdPremium, String priceIdUnlimited) {
        return new StripeGatewayClient(
            RestClient.builder(),
            API_BASE_URL,
            secretKey,
            PUBLISHABLE_KEY,
            webhookSecret,
            priceIdPremium,
            priceIdUnlimited
        );
    }

    private StripeGatewayClient configuredClient() {
        return client(SECRET_KEY, WEBHOOK_SECRET, PRICE_PREMIUM, PRICE_UNLIMITED);
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String signatureHeader(String secret, String body) {
        long t = Instant.now().getEpochSecond();
        return "t=" + t + ",v1=" + hmacSha256Hex(secret, t + "." + body);
    }

    // ---- surface ----

    @Test
    void gatewayIdentityAndCapabilities() {
        StripeGatewayClient client = configuredClient();
        assertEquals("stripe", client.gatewayName());
        assertEquals("Stripe-Signature", client.webhookSignatureHeader());
        assertEquals(CheckoutMode.REDIRECT, client.checkoutMode());
        assertEquals(PUBLISHABLE_KEY, client.publicKeyId());
        assertTrue(client.capabilities().contains(GatewayCapability.SUBSCRIPTIONS));
        assertTrue(client.capabilities().contains(GatewayCapability.USAGE_ADDONS));
        assertFalse(client.capabilities().contains(GatewayCapability.ONE_TIME_ORDERS));
    }

    @Test
    void isConfiguredOnlyWithSecretKey() {
        assertFalse(client("", WEBHOOK_SECRET, PRICE_PREMIUM, PRICE_UNLIMITED).isConfigured());
        assertFalse(client("   ", WEBHOOK_SECRET, PRICE_PREMIUM, PRICE_UNLIMITED).isConfigured());
        assertTrue(configuredClient().isConfigured());
    }

    // ---- webhook signature verification ----

    @Test
    void verifiesAValidSignature() {
        String body = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"data\":{\"object\":{}}}";
        assertDoesNotThrow(
            () -> configuredClient().verifyWebhookSignature(body, signatureHeader(WEBHOOK_SECRET, body)));
    }

    @Test
    void rejectsAWrongSignature() {
        String body = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"data\":{\"object\":{}}}";
        long t = Instant.now().getEpochSecond();
        String forged = "t=" + t + ",v1=" + hmacSha256Hex("whsec_attacker", t + "." + body);
        assertThrows(InvalidWebhookSignatureException.class,
            () -> configuredClient().verifyWebhookSignature(body, forged));
    }

    @Test
    void rejectsATamperedBody() {
        String body = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"data\":{\"object\":{}}}";
        String header = signatureHeader(WEBHOOK_SECRET, body);
        assertThrows(InvalidWebhookSignatureException.class,
            () -> configuredClient().verifyWebhookSignature(body + " ", header));
    }

    @Test
    void rejectsMissingAndMalformedHeaders() {
        String body = "{}";
        assertThrows(InvalidWebhookSignatureException.class,
            () -> configuredClient().verifyWebhookSignature(body, null));
        assertThrows(InvalidWebhookSignatureException.class,
            () -> configuredClient().verifyWebhookSignature(body, ""));
        assertThrows(InvalidWebhookSignatureException.class,
            () -> configuredClient().verifyWebhookSignature(body, "no-keyvalue-pairs"));
        // v1 present but no t=
        assertThrows(InvalidWebhookSignatureException.class,
            () -> configuredClient().verifyWebhookSignature(body, "v1=abc123"));
        // t present but not numeric, and no v1
        assertThrows(InvalidWebhookSignatureException.class,
            () -> configuredClient().verifyWebhookSignature(body, "t=notanumber,v1=abc"));
    }

    @Test
    void rejectsAReplayedSignatureOutsideTolerance() {
        String body = "{}";
        long stale = Instant.now().getEpochSecond() - 400;
        String header = "t=" + stale + ",v1=" + hmacSha256Hex(WEBHOOK_SECRET, stale + "." + body);
        assertThrows(InvalidWebhookSignatureException.class,
            () -> configuredClient().verifyWebhookSignature(body, header));
    }

    @Test
    void failsClosedWhenWebhookSecretMissing() {
        String body = "{}";
        StripeGatewayClient noSecret = client(SECRET_KEY, "", PRICE_PREMIUM, PRICE_UNLIMITED);
        // A perfectly formed header - signed with a publicly guessable secret,
        // the exact attack an empty configured secret enables - must still
        // be rejected because the server has no secret to check against.
        String header = signatureHeader("whsec_public", body);
        assertThrows(InvalidWebhookSignatureException.class,
            () -> noSecret.verifyWebhookSignature(body, header));
    }

    // ---- webhook event parsing ----

    @Test
    void mapsSubscriptionLifecycleEvents() {
        StripeGatewayClient client = configuredClient();
        String deleted = "{\"type\":\"customer.subscription.deleted\","
            + "\"data\":{\"object\":{\"id\":\"sub_123\"}}}";
        BillingEvent event = client.parseWebhookEvent(deleted);
        assertEquals(BillingEventType.SUBSCRIPTION_CANCELLED, event.type());
        assertEquals("sub_123", event.gatewaySubscriptionId());
        assertEquals("stripe", event.gatewayName());
        assertNull(event.capturedPayment());

        String created = "{\"type\":\"customer.subscription.created\","
            + "\"data\":{\"object\":{\"id\":\"sub_456\"}}}";
        assertEquals(BillingEventType.SUBSCRIPTION_AUTHENTICATED,
            client.parseWebhookEvent(created).type());
        assertEquals(BillingEventType.SUBSCRIPTION_UPDATED,
            client.parseWebhookEvent("{\"type\":\"customer.subscription.updated\","
                + "\"data\":{\"object\":{\"id\":\"sub_1\"}}}").type());
        assertEquals(BillingEventType.SUBSCRIPTION_PAUSED,
            client.parseWebhookEvent("{\"type\":\"customer.subscription.paused\","
                + "\"data\":{\"object\":{\"id\":\"sub_1\"}}}").type());
        assertEquals(BillingEventType.SUBSCRIPTION_RESUMED,
            client.parseWebhookEvent("{\"type\":\"customer.subscription.resumed\","
                + "\"data\":{\"object\":{\"id\":\"sub_1\"}}}").type());
    }

    @Test
    void mapsInvoicePaidWithCapturedPayment() {
        String body = "{\"type\":\"invoice.paid\",\"data\":{\"object\":{"
            + "\"id\":\"in_999\",\"subscription\":\"sub_legacy\",\"amount_paid\":49900,"
            + "\"currency\":\"usd\"}}}";
        BillingEvent event = configuredClient().parseWebhookEvent(body);
        assertEquals(BillingEventType.SUBSCRIPTION_CHARGED, event.type());
        assertEquals("sub_legacy", event.gatewaySubscriptionId());
        assertNotNull(event.capturedPayment());
        assertEquals("in_999", event.capturedPayment().paymentId());
        assertEquals(49900L, event.capturedPayment().amountMinor());
        assertEquals("USD", event.capturedPayment().currency());
    }

    @Test
    void prefersTheApi2025ParentSubscriptionShape() {
        String body = "{\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{"
            + "\"parent\":{\"subscription_details\":{\"subscription\":\"sub_new\"}}}}}";
        BillingEvent event = configuredClient().parseWebhookEvent(body);
        assertEquals(BillingEventType.SUBSCRIPTION_PENDING, event.type());
        assertEquals("sub_new", event.gatewaySubscriptionId());
    }

    @Test
    void invoicePaidWithoutASubscriptionCarriesNoCapturedPayment() {
        // A one-off invoice (no subscription) must not be misread as a renewal charge.
        String body = "{\"type\":\"invoice.paid\",\"data\":{\"object\":{"
            + "\"id\":\"in_1\",\"amount_paid\":100,\"currency\":\"usd\"}}}";
        BillingEvent event = configuredClient().parseWebhookEvent(body);
        assertEquals(BillingEventType.SUBSCRIPTION_CHARGED, event.type());
        assertNull(event.gatewaySubscriptionId());
        assertNull(event.capturedPayment());
    }

    @Test
    void unrecognisedAndBrokenPayloadsMapToUnknown() {
        StripeGatewayClient client = configuredClient();
        assertEquals(BillingEventType.UNKNOWN,
            client.parseWebhookEvent("{\"type\":\"charge.refunded\",\"data\":{\"object\":{}}}").type());
        assertEquals(BillingEventType.UNKNOWN,
            client.parseWebhookEvent("this is not json").type());
    }

    // ---- configuration fail-fast ----

    @Test
    void unconfiguredClientFailsLoudlyBeforeAnyHttp() {
        StripeGatewayClient off = client("", "", "", "");
        assertThrows(PaymentGatewayException.class,
            () -> off.createSubscriptionWithTrial("t1", "PREMIUM", 14, "a@b.c", "A"));
        assertThrows(PaymentGatewayException.class,
            () -> off.createOverageAddon("sub_1", "overage", 100, "USD"));
        assertThrows(PaymentGatewayException.class,
            () -> off.cancelSubscription("sub_1", true));
    }

    @Test
    void missingPriceIdNamesTheMissingProperty() {
        StripeGatewayClient noPrices = client(SECRET_KEY, WEBHOOK_SECRET, "", "");
        PaymentGatewayException e = assertThrows(PaymentGatewayException.class,
            () -> noPrices.createSubscriptionWithTrial("t1", "PREMIUM", 14, null, null));
        assertTrue(e.getMessage().contains("STRIPE_PRICE_ID_PREMIUM"));

        e = assertThrows(PaymentGatewayException.class,
            () -> noPrices.createSubscriptionWithTrial("t1", "UNLIMITED", 0, null, null));
        assertTrue(e.getMessage().contains("STRIPE_PRICE_ID_UNLIMITED"));

        // An unknown plan name resolves through the UNLIMITED hint branch and still fails loudly.
        e = assertThrows(PaymentGatewayException.class,
            () -> configuredClient().createSubscriptionWithTrial("t1", "NO_SUCH_PLAN", 0, null, null));
        assertTrue(e.getMessage().contains("NO_SUCH_PLAN"));
    }
}
