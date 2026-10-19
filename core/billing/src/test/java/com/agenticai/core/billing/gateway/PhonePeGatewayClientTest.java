package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Covers only the deterministic, no-network-call logic of {@link PhonePeGatewayClient} - the
 * same scoping precedent {@link RazorpayGatewayClientTest} already established for its own
 * gateway. {@link PhonePeGatewayClient#obtainAccessToken()} and the actual HTTP call inside
 * {@link PhonePeGatewayClient#createSubscriptionWithTrial} are NOT exercised here (they depend on
 * {@code RestClient}'s fluent chain against a real or mocked HTTP server, and this class's own
 * javadoc already flags the exact API base path as unconfirmed - not worth mocking a call shape
 * that isn't independently verified yet). What IS covered:
 *
 * <ul>
 *   <li>{@link PhonePeGatewayClient#verifyWebhookSignature} - PhonePe's
 *       SHA256(username:password)-against-Authorization-header scheme, a real security boundary
 *       exactly like {@link RazorpayGatewayClientTest}'s HMAC coverage of the Razorpay
 *       equivalent.
 *   <li>{@link PhonePeGatewayClient#parseWebhookEvent} - JSON-to-{@link BillingEvent} mapping,
 *       including the deliberate default-to-{@link BillingEventType#UNKNOWN} behavior for every
 *       event name this class hasn't independently confirmed.
 *   <li>{@link PhonePeGatewayClient#createOverageAddon} - documented as unsupported rather than
 *       guessed; locks in that it fails loudly, not silently.
 *   <li>The NPCI ₹15,000 UPI Autopay per-mandate ceiling check inside {@link
 *       PhonePeGatewayClient#createSubscriptionWithTrial} - this check runs, and throws, before
 *       any OAuth token fetch or HTTP call is attempted, so it's reachable without a network
 *       double. The plan-configuration parsing ({@link
 *       PhonePeGatewayClient#createSubscriptionWithTrial}'s call into the private {@code
 *       parsePlanConfig}) is exercised the same way, via its observable exception behavior.
 * </ul>
 */
class PhonePeGatewayClientTest {

    private static final String OAUTH_BASE_URL = "https://api-preprod.phonepe.com/apis/pg-sandbox";
    private static final String API_BASE_URL = "https://api-preprod.phonepe.com/apis/pg-sandbox";
    private static final String CLIENT_ID = "dummy_client_id";
    private static final String CLIENT_SECRET = "dummy_client_secret";
    private static final String CLIENT_VERSION = "1";
    private static final String MERCHANT_ID = "dummy_merchant_id";
    private static final String WEBHOOK_USERNAME = "dummy_webhook_username";
    private static final String WEBHOOK_PASSWORD = "dummy_webhook_password";

    private PhonePeGatewayClient client(String premiumPlanConfig, String unlimitedPlanConfig) {
        return new PhonePeGatewayClient(
            org.springframework.web.client.RestClient.builder(),
            OAUTH_BASE_URL,
            API_BASE_URL,
            CLIENT_ID,
            CLIENT_SECRET,
            CLIENT_VERSION,
            MERCHANT_ID,
            WEBHOOK_USERNAME,
            WEBHOOK_PASSWORD,
            premiumPlanConfig,
            unlimitedPlanConfig
        );
    }

    private static String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- gateway identity -------------------------------------------------

    @Test
    void gatewayNameIsPhonepe() {
        assertEquals("phonepe", client("99900:MONTHLY", "199900:MONTHLY").gatewayName());
    }

    @Test
    void publicKeyIdReturnsTheConfiguredMerchantIdNotAnActualSecret() {
        // See publicKeyId()'s own javadoc note - PhonePe has no client-side-widget key analogous
        // to Razorpay's Key ID, so merchantId (already customer-visible on PhonePe's hosted
        // PayPage) is returned instead, deliberately not something more sensitive.
        assertEquals(MERCHANT_ID, client("99900:MONTHLY", "199900:MONTHLY").publicKeyId());
    }

    // --- verifyWebhookSignature ---------------------------------------------

    @Test
    void verifyWebhookSignatureAcceptsTheCorrectSha256OfConfiguredCredentials() {
        String correctHeader = sha256Hex(WEBHOOK_USERNAME + ":" + WEBHOOK_PASSWORD);
        assertDoesNotThrow(() ->
            client("99900:MONTHLY", "199900:MONTHLY").verifyWebhookSignature("{}", correctHeader)
        );
    }

    @Test
    void verifyWebhookSignatureAcceptsTheCorrectHashRegardlessOfCase() {
        // signatureHeader is lower-cased before comparison; HexFormat already emits lowercase,
        // but an upstream sender that happens to send uppercase hex should still verify.
        String correctHeaderUpper = sha256Hex(WEBHOOK_USERNAME + ":" + WEBHOOK_PASSWORD).toUpperCase();
        assertDoesNotThrow(() ->
            client("99900:MONTHLY", "199900:MONTHLY").verifyWebhookSignature("{}", correctHeaderUpper)
        );
    }

    @Test
    void verifyWebhookSignatureRejectsAWrongHash() {
        assertThrows(InvalidWebhookSignatureException.class, () ->
            client("99900:MONTHLY", "199900:MONTHLY").verifyWebhookSignature("{}", "not-the-right-hash")
        );
    }

    @Test
    void verifyWebhookSignatureRejectsAMissingHeader() {
        assertThrows(InvalidWebhookSignatureException.class, () ->
            client("99900:MONTHLY", "199900:MONTHLY").verifyWebhookSignature("{}", null)
        );
    }

    @Test
    void verifyWebhookSignatureRejectsABlankHeader() {
        assertThrows(InvalidWebhookSignatureException.class, () ->
            client("99900:MONTHLY", "199900:MONTHLY").verifyWebhookSignature("{}", "   ")
        );
    }

    // --- SEC-024: fail closed while webhook credentials are unset ----------

    private PhonePeGatewayClient clientWithWebhookCreds(String username, String password) {
        return new PhonePeGatewayClient(
            org.springframework.web.client.RestClient.builder(),
            OAUTH_BASE_URL, API_BASE_URL, CLIENT_ID, CLIENT_SECRET, CLIENT_VERSION, MERCHANT_ID,
            username, password, "99900:MONTHLY", "199900:MONTHLY");
    }

    @Test
    void sec024BlankCredentialsRejectTheWellKnownSha256OfColon() {
        // The attack: with both credentials blank the old expected value was sha256(":"),
        // a public constant any caller could send.
        String publicConstant = sha256Hex(":");
        assertThrows(InvalidWebhookSignatureException.class, () ->
            clientWithWebhookCreds("", "").verifyWebhookSignature("{}", publicConstant));
        assertThrows(InvalidWebhookSignatureException.class, () ->
            clientWithWebhookCreds(null, null).verifyWebhookSignature("{}", publicConstant));
        assertThrows(InvalidWebhookSignatureException.class, () ->
            clientWithWebhookCreds("  ", "  ").verifyWebhookSignature("{}", sha256Hex("  :  ")));
    }

    @Test
    void sec024OneBlankCredentialAlsoFailsClosed() {
        assertThrows(InvalidWebhookSignatureException.class, () ->
            clientWithWebhookCreds("user", "").verifyWebhookSignature("{}", sha256Hex("user:")));
        assertThrows(InvalidWebhookSignatureException.class, () ->
            clientWithWebhookCreds("", "pass").verifyWebhookSignature("{}", sha256Hex(":pass")));
    }

    @Test
    void sec024ConfiguredCredentialsAcceptCorrectAndRejectWrong() {
        PhonePeGatewayClient c = clientWithWebhookCreds("u1", "p1");
        assertDoesNotThrow(() -> c.verifyWebhookSignature("{}", sha256Hex("u1:p1")));
        assertThrows(InvalidWebhookSignatureException.class, () ->
            c.verifyWebhookSignature("{}", sha256Hex("u1:p2")));
        assertThrows(InvalidWebhookSignatureException.class, () ->
            c.verifyWebhookSignature("{}", sha256Hex(":")));
    }

    // --- parseWebhookEvent: event-type mapping -----------------------------

    @Test
    void parseWebhookEventMapsConfirmedCheckoutCompletedToSubscriptionCharged() {
        String body = "{\"event\":\"checkout.order.completed\",\"payload\":{\"orderId\":\"order_123\"}}";
        BillingEvent event = client("99900:MONTHLY", "199900:MONTHLY").parseWebhookEvent(body);
        assertEquals(BillingEventType.SUBSCRIPTION_CHARGED, event.type());
        assertEquals("order_123", event.gatewaySubscriptionId());
        assertEquals("checkout.order.completed", event.rawEventName());
    }

    @Test
    void parseWebhookEventMapsConfirmedCheckoutFailedToSubscriptionPending() {
        String body = "{\"event\":\"checkout.order.failed\",\"payload\":{\"orderId\":\"order_456\"}}";
        BillingEvent event = client("99900:MONTHLY", "199900:MONTHLY").parseWebhookEvent(body);
        assertEquals(BillingEventType.SUBSCRIPTION_PENDING, event.type());
        assertEquals("order_456", event.gatewaySubscriptionId());
    }

    @Test
    void parseWebhookEventFallsBackToSubscriptionIdWhenOrderIdIsAbsent() {
        String body = "{\"event\":\"checkout.order.completed\",\"payload\":{\"subscriptionId\":\"sub_789\"}}";
        BillingEvent event = client("99900:MONTHLY", "199900:MONTHLY").parseWebhookEvent(body);
        assertEquals("sub_789", event.gatewaySubscriptionId());
    }

    @Test
    void parseWebhookEventMapsRefundEventsToUnknownRatherThanAGuessedType() {
        // Confirmed as real event names, but not confirmed which BillingEventType they should
        // normalize to - see this class's javadoc, "NOT CONFIRMED" item 2.
        String body = "{\"event\":\"pg.refund.completed\",\"payload\":{\"orderId\":\"order_1\"}}";
        BillingEvent event = client("99900:MONTHLY", "199900:MONTHLY").parseWebhookEvent(body);
        assertEquals(BillingEventType.UNKNOWN, event.type());
    }

    @Test
    void parseWebhookEventMapsAnyUnconfirmedSubscriptionLifecycleEventNameToUnknown() {
        // e.g. a hypothetical Autopay "subscription.activated" - never independently confirmed,
        // so it must NOT silently misfile as something it might not be.
        String body = "{\"event\":\"subscription.activated\",\"payload\":{\"subscriptionId\":\"sub_1\"}}";
        BillingEvent event = client("99900:MONTHLY", "199900:MONTHLY").parseWebhookEvent(body);
        assertEquals(BillingEventType.UNKNOWN, event.type());
    }

    @Test
    void parseWebhookEventOnMalformedJsonReturnsUnknownWithNoSubscriptionId() {
        BillingEvent event = client("99900:MONTHLY", "199900:MONTHLY").parseWebhookEvent("not json at all");
        assertEquals(BillingEventType.UNKNOWN, event.type());
        assertNull(event.gatewaySubscriptionId());
        assertEquals("", event.rawEventName());
    }

    // --- createOverageAddon: honestly unimplemented -------------------------

    @Test
    void createOverageAddonThrowsRatherThanGuessingAnUnconfirmedApi() {
        PhonePeGatewayClient client = client("99900:MONTHLY", "199900:MONTHLY");
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, () ->
            client.createOverageAddon("sub_1", "overage", 5000, "INR")
        );
        assertTrue(ex.getMessage().contains("Razorpay"));
    }

    // --- createSubscriptionWithTrial: NPCI ceiling + plan-config parsing ----
    // (reachable without a network call - both throw before obtainAccessToken() runs)

    @Test
    void createSubscriptionWithTrialThrowsWhenPlanAmountExceedsTheNpciAutopayCeiling() {
        // 20,000.00 INR = 2,000,000 paise > the 1,500,000-paise (₹15,000) NPCI Autopay ceiling.
        PhonePeGatewayClient client = client("2000000:MONTHLY", "199900:MONTHLY");
        PaymentGatewayException ex = assertThrows(PaymentGatewayException.class, () ->
            client.createSubscriptionWithTrial("tenant-1", "premium", 0, "a@example.com", "A")
        );
        assertTrue(ex.getMessage().contains("NPCI"));
    }

    @Test
    void createSubscriptionWithTrialThrowsWhenNoPlanConfigurationIsFoundForTheGivenPlanId() {
        PhonePeGatewayClient client = client("", "");
        assertThrows(PaymentGatewayException.class, () ->
            client.createSubscriptionWithTrial("tenant-1", "premium", 0, "a@example.com", "A")
        );
    }

    @Test
    void createSubscriptionWithTrialThrowsOnMalformedPlanConfiguration() {
        PhonePeGatewayClient client = client("not-a-valid-format", "199900:MONTHLY");
        assertThrows(PaymentGatewayException.class, () ->
            client.createSubscriptionWithTrial("tenant-1", "premium", 0, "a@example.com", "A")
        );
    }

    @Test
    void createSubscriptionWithTrialThrowsOnNonNumericPlanAmount() {
        PhonePeGatewayClient client = client("abc:MONTHLY", "199900:MONTHLY");
        assertThrows(PaymentGatewayException.class, () ->
            client.createSubscriptionWithTrial("tenant-1", "premium", 0, "a@example.com", "A")
        );
    }
}
