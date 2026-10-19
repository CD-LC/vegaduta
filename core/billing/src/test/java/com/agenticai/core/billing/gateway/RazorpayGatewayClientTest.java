package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * {@link RazorpayGatewayClient} is this app's only integration with real money - a bug in {@link
 * #verifyWebhookSignature} accepting a forged webhook could let an attacker "activate" a
 * subscription without ever paying; a bug in {@link #parseWebhookEvent}'s
 * event-name/subscription-id extraction could silently misfile a real payment event. Both had zero
 * test coverage before this suite.
 *
 * <p>Test keys/secrets below are dummy values, never real Razorpay credentials - constructing
 * {@link RazorpayGatewayClient} directly (not through Spring) only exercises {@code
 * com.razorpay.RazorpayClient}'s local constructor (credential storage + HTTP client setup), which
 * per the Razorpay Java SDK does not itself make a network call - the class's own javadoc already
 * notes it "needs a real compile pass plus... this sandbox can't execute directly" for the parts
 * that do call out to Razorpay; this suite only covers the deterministic, no-network logic ({@link
 * #verifyWebhookSignature}'s HMAC check and {@link #parseWebhookEvent}'s JSON mapping), which is
 * exactly the part safe and meaningful to unit test without a live account.
 */
class RazorpayGatewayClientTest {

    private static final String KEY_ID = "rzp_test_dummy_key_id";
    private static final String KEY_SECRET = "dummy_key_secret";
    private static final String WEBHOOK_SECRET = "dummy_webhook_secret";

    private final RazorpayGatewayClient client =
            new RazorpayGatewayClient(KEY_ID, KEY_SECRET, WEBHOOK_SECRET);

    // --- gateway identity -------------------------------------------------

    @Test
    void gatewayNameIsRazorpay() {
        assertEquals("razorpay", client.gatewayName());
    }

    @Test
    void publicKeyIdReturnsConfiguredKeyIdVerbatim() {
        // Never the secret - only the Key ID is safe to hand to the
        // browser for the client-side checkout widget (see
        // PaymentGatewayClient#publicKeyId's javadoc).
        assertEquals(KEY_ID, client.publicKeyId());
    }

    // --- plan / coupon resolution (the gateway-neutral upgrade surface) ---

    @Test
    void planAndCouponLookupsDelegateToThePlanCatalog() {
        RazorpayPlanCatalog catalog = new RazorpayPlanCatalog();
        catalog.getPlans().getUnlimited().getUsd().setAnnual("plan_unl_usd_annual");
        catalog.getOffers().put("launch50", "offer_abc123");
        RazorpayGatewayClient withCatalog =
                new RazorpayGatewayClient(KEY_ID, KEY_SECRET, WEBHOOK_SECRET, catalog);

        assertEquals(EnumSet.allOf(BillingCurrency.class), withCatalog.supportedCurrencies());
        assertEquals(EnumSet.allOf(BillingPeriod.class), withCatalog.supportedPeriods());
        assertTrue(withCatalog.supportsCoupons());
        assertEquals(
                "plan_unl_usd_annual",
                withCatalog.resolvePlanId(
                        PaidTier.UNLIMITED, BillingCurrency.USD, BillingPeriod.ANNUAL));
        assertEquals(Optional.of("offer_abc123"), withCatalog.offerIdFor("LAUNCH50"));
        assertEquals(Optional.empty(), withCatalog.offerIdFor("NOPE99"));
        // Offered = currencies whose monthly plans exist for every tier; only USD annual is
        // half-configured here.
        assertEquals(java.util.List.of(), withCatalog.offeredCurrencies());
        assertEquals(java.util.List.of(), withCatalog.annualCurrencies());
        catalog.getPlans().getPremium().getUsd().setMonthly("p_u");
        catalog.getPlans().getUnlimited().getUsd().setMonthly("u_u");
        assertEquals(java.util.List.of(BillingCurrency.USD), withCatalog.offeredCurrencies());
    }

    // --- parseWebhookEvent: event-type mapping -----------------------------

    @Test
    void allTenDocumentedRazorpayEventNamesMapToTheirBillingEventType() {
        Map<String, BillingEventType> expected =
                Map.ofEntries(
                        Map.entry(
                                "subscription.authenticated",
                                BillingEventType.SUBSCRIPTION_AUTHENTICATED),
                        Map.entry(
                                "subscription.activated", BillingEventType.SUBSCRIPTION_ACTIVATED),
                        Map.entry("subscription.charged", BillingEventType.SUBSCRIPTION_CHARGED),
                        Map.entry("subscription.pending", BillingEventType.SUBSCRIPTION_PENDING),
                        Map.entry("subscription.halted", BillingEventType.SUBSCRIPTION_HALTED),
                        Map.entry("subscription.paused", BillingEventType.SUBSCRIPTION_PAUSED),
                        Map.entry("subscription.resumed", BillingEventType.SUBSCRIPTION_RESUMED),
                        Map.entry(
                                "subscription.cancelled", BillingEventType.SUBSCRIPTION_CANCELLED),
                        Map.entry(
                                "subscription.completed", BillingEventType.SUBSCRIPTION_COMPLETED),
                        Map.entry("subscription.updated", BillingEventType.SUBSCRIPTION_UPDATED));

        expected.forEach(
                (eventName, expectedType) -> {
                    BillingEvent event =
                            client.parseWebhookEvent(webhookBody(eventName, "sub_abc123"));
                    assertEquals(expectedType, event.type(), "wrong mapping for " + eventName);
                    assertEquals(eventName, event.rawEventName());
                });
    }

    @Test
    void unrecognizedEventNameMapsToUnknownButKeepsRawName() {
        BillingEvent event =
                client.parseWebhookEvent(
                        webhookBody("subscription.some_future_event", "sub_abc123"));

        assertEquals(BillingEventType.UNKNOWN, event.type());
        // The raw name is deliberately preserved even for UNKNOWN - see
        // BillingEvent's javadoc - so it's diagnosable from a log line
        // without re-fetching the original webhook body.
        assertEquals("subscription.some_future_event", event.rawEventName());
    }

    @Test
    void missingEventKeyMapsToUnknownWithEmptyRawName() {
        BillingEvent event = client.parseWebhookEvent("{\"payload\":{}}");

        assertEquals(BillingEventType.UNKNOWN, event.type());
        assertEquals("", event.rawEventName());
    }

    // --- parseWebhookEvent: subscription id extraction ---------------------

    @Test
    void extractsSubscriptionIdFromWellFormedPayload() {
        BillingEvent event =
                client.parseWebhookEvent(webhookBody("subscription.charged", "sub_xyz789"));

        assertEquals("sub_xyz789", event.gatewaySubscriptionId());
    }

    @Test
    void missingPayloadKeyYieldsNullSubscriptionIdWithoutThrowing() {
        BillingEvent event = client.parseWebhookEvent("{\"event\":\"subscription.charged\"}");

        assertNull(event.gatewaySubscriptionId());
        assertEquals(BillingEventType.SUBSCRIPTION_CHARGED, event.type());
    }

    @Test
    void payloadPresentButMissingSubscriptionKeyYieldsNullSubscriptionId() {
        BillingEvent event =
                client.parseWebhookEvent("{\"event\":\"subscription.charged\",\"payload\":{}}");

        assertNull(event.gatewaySubscriptionId());
    }

    @Test
    void subscriptionPresentButMissingEntityKeyYieldsNullSubscriptionId() {
        BillingEvent event =
                client.parseWebhookEvent(
                        "{\"event\":\"subscription.charged\",\"payload\":{\"subscription\":{}}}");

        assertNull(event.gatewaySubscriptionId());
    }

    @Test
    void entityPresentButMissingIdYieldsNullSubscriptionId() {
        BillingEvent event =
                client.parseWebhookEvent(
                        "{\"event\":\"subscription.charged\",\"payload\":{\"subscription\":{\"entity\":{\"status\":\"active\"}}}}");

        assertNull(event.gatewaySubscriptionId());
    }

    // --- parseWebhookEvent: UPI-collect follow-up event mapping ------------

    @Test
    void paymentLinkPaidMapsToPaymentLinkPaidAndExtractsPaymentLinkId() {
        String body =
                "{\"event\":\"payment_link.paid\","
                        + "\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"plink_abc123\",\"status\":\"paid\"}},"
                        + "\"payment\":{\"entity\":{\"id\":\"pay_xyz\"}}}}";

        BillingEvent event = client.parseWebhookEvent(body);

        assertEquals(BillingEventType.PAYMENT_LINK_PAID, event.type());
        assertEquals("payment_link.paid", event.rawEventName());
        // The Payment Link id, not the nested payment id - see
        // extractReferenceId's javadoc-comment in RazorpayGatewayClient.
        assertEquals("plink_abc123", event.gatewaySubscriptionId());
    }

    @Test
    void qrCodeCreditedMapsToQrCodePaymentReceivedAndExtractsQrCodeId() {
        String body =
                "{\"event\":\"qr_code.credited\","
                        + "\"payload\":{\"qr_code\":{\"entity\":{\"id\":\"qr_abc123\",\"status\":\"active\"}},"
                        + "\"payment\":{\"entity\":{\"id\":\"pay_xyz\"}}}}";

        BillingEvent event = client.parseWebhookEvent(body);

        assertEquals(BillingEventType.QR_CODE_PAYMENT_RECEIVED, event.type());
        assertEquals("qr_code.credited", event.rawEventName());
        assertEquals("qr_abc123", event.gatewaySubscriptionId());
    }

    @Test
    void qrCodeClosedIsUnmappedAndCarriesNoReferenceId() {
        // Only qr_code.credited is mapped/actionable today - .created/.closed
        // fall through to UNKNOWN, same as any other undocumented-for-this-app
        // event, and deliberately don't extract a reference id (payloadKeyFor
        // only recognizes payment_link.paid and qr_code.credited).
        BillingEvent event =
                client.parseWebhookEvent(
                        "{\"event\":\"qr_code.closed\",\"payload\":{\"qr_code\":{\"entity\":{\"id\":\"qr_abc123\"}}}}");

        assertEquals(BillingEventType.UNKNOWN, event.type());
        assertNull(event.gatewaySubscriptionId());
    }

    private static String webhookBody(String eventName, String subscriptionId) {
        return "{\"event\":\""
                + eventName
                + "\","
                + "\"payload\":{\"subscription\":{\"entity\":{\"id\":\""
                + subscriptionId
                + "\",\"status\":\"active\"}}}}";
    }

    // --- verifyWebhookSignature: HMAC-SHA256 over the raw body -------------
    //
    // Razorpay's documented webhook verification scheme (razorpay.com/docs/
    // webhooks/validate-test): the X-Razorpay-Signature header is a hex
    // HMAC-SHA256 digest of the exact raw request body, keyed with the
    // webhook secret. Computed independently here (not by calling into the
    // Razorpay SDK) so this test doesn't just re-assert whatever the SDK
    // happens to do - it pins down the actual documented contract.

    private static String hmacSha256Hex(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Test
    void validSignatureIsAcceptedWithoutThrowing() throws Exception {
        String body = "{\"event\":\"subscription.charged\"}";
        String validSignature = hmacSha256Hex(body, WEBHOOK_SECRET);

        assertDoesNotThrow(() -> client.verifyWebhookSignature(body, validSignature));
    }

    @Test
    void signatureComputedWithWrongSecretIsRejected() throws Exception {
        String body = "{\"event\":\"subscription.charged\"}";
        String signatureFromWrongSecret = hmacSha256Hex(body, "not_the_real_secret");

        assertThrows(
                InvalidWebhookSignatureException.class,
                () -> client.verifyWebhookSignature(body, signatureFromWrongSecret));
    }

    @Test
    void tamperedBodyWithOriginallyValidSignatureIsRejected() throws Exception {
        // The exact attack this check exists to prevent: an attacker who
        // can see one legitimate webhook cannot replay its signature
        // against a modified body (e.g. changing which subscription/tenant
        // an "activated" event refers to).
        String originalBody = "{\"event\":\"subscription.activated\"}";
        String signature = hmacSha256Hex(originalBody, WEBHOOK_SECRET);
        String tamperedBody = "{\"event\":\"subscription.cancelled\"}";

        assertThrows(
                InvalidWebhookSignatureException.class,
                () -> client.verifyWebhookSignature(tamperedBody, signature));
    }

    @Test
    void garbageSignatureIsRejected() {
        assertThrows(
                InvalidWebhookSignatureException.class,
                () ->
                        client.verifyWebhookSignature(
                                "{\"event\":\"subscription.charged\"}", "not-a-real-signature"));
    }

    @Test
    void emptySignatureIsRejected() {
        assertThrows(
                InvalidWebhookSignatureException.class,
                () -> client.verifyWebhookSignature("{\"event\":\"subscription.charged\"}", ""));
    }
}
