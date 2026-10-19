package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * B9-PR4 revenue-path contract - the signature gate on {@link
 * BillingWebhookController}, the SECOND of the two webhook entry points
 * (the first, {@code /webhook/razorpay}, is pinned by {@code
 * RazorpayWebhookControllerSignatureTest} in core/tenancy). Unlike that
 * controller (which lets a signature failure escape to the global 403
 * mapper), this one owns its status codes - 401 on a bad signature, 404 on
 * an unknown gateway, 500 on a handler failure (claim released so the
 * gateway retries), 200 otherwise - so those codes are part of the contract
 * and asserted here.
 *
 * <p>Built on a REAL {@link RazorpayGatewayClient} resolved through a REAL
 * {@link PaymentGatewayRegistry} (only the dispatcher is mocked), with the
 * X-Razorpay-Signature computed independently via HMAC-SHA256 in this test
 * - pinning the documented algorithm, not echoing a mocked verifier.
 */
class BillingWebhookControllerContractTest {

    private static final String WEBHOOK_SECRET = "test_webhook_secret";
    private static final String BODY =
        "{\"event\":\"subscription.charged\",\"payload\":{\"subscription\":{\"entity\":{\"id\":\"sub_42\"}}}}";

    private final RazorpayGatewayClient razorpay =
        new RazorpayGatewayClient("rzp_test_dummy_key_id", "dummy_key_secret", WEBHOOK_SECRET);
    private final PaymentGatewayRegistry registry =
        new PaymentGatewayRegistry(List.of(razorpay), "razorpay");
    private final BillingEventDispatcher dispatcher = mock(BillingEventDispatcher.class);
    private final BillingWebhookEventLedger eventLedger = mock(BillingWebhookEventLedger.class);
    private final BillingWebhookController controller =
        new BillingWebhookController(registry, dispatcher, eventLedger);

    /** Independent HMAC (not the Razorpay SDK) so the test pins the actual documented scheme. */
    private static String hmacSha256Hex(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validSignatureReturns200AndDispatchesTheParsedEvent() throws Exception {
        ResponseEntity<Void> response =
            controller.handleWebhook("razorpay", BODY, razorpayHeaders(hmacSha256Hex(BODY, WEBHOOK_SECRET), "evt_1"));

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<BillingEvent> captor = ArgumentCaptor.forClass(BillingEvent.class);
        verify(dispatcher).dispatch(captor.capture());
        // Parse happens strictly AFTER verification and must carry the id
        // the handlers key their DB writes on.
        assertEquals(BillingEventType.SUBSCRIPTION_CHARGED, captor.getValue().type());
        assertEquals("sub_42", captor.getValue().gatewaySubscriptionId());
        assertEquals("subscription.charged", captor.getValue().rawEventName());
    }

    @Test
    void tamperedBodyReturns401AndNothingIsDispatched() throws Exception {
        // Replay attack: a real delivery's signature reused over a body
        // edited to target a different subscription. If this dispatched,
        // anyone who ever saw one legitimate webhook could mark arbitrary
        // subscriptions ACTIVE.
        String signatureOfOriginal = hmacSha256Hex(BODY, WEBHOOK_SECRET);
        String tampered = BODY.replace("sub_42", "sub_attacker");

        ResponseEntity<Void> response = controller.handleWebhook("razorpay", tampered, razorpayHeaders(signatureOfOriginal, "evt_1"));

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void signatureFromTheWrongSecretReturns401AndNothingIsDispatched() throws Exception {
        String forged = hmacSha256Hex(BODY, "guessed_wrong_secret");

        ResponseEntity<Void> response = controller.handleWebhook("razorpay", BODY, razorpayHeaders(forged, "evt_1"));

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void missingSignatureHeaderIs401AndNeverDispatches() {
        // Hardened (JAVA-003/004): a header-less POST is an auth failure, not
        // an internal error - 401, and the invariant that nothing is
        // dispatched still holds.
        ResponseEntity<Void> response = controller.handleWebhook("razorpay", BODY, razorpayHeaders(null, null));

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void unknownGatewayReturns404WithoutTouchingVerificationOrDispatch() throws Exception {
        // Even a VALID Razorpay signature must not be accepted under a
        // gateway name with no registered client - resolve() throwing (not
        // falling back to the default) is what prevents cross-gateway
        // misrouting of real money events.
        ResponseEntity<Void> response =
            controller.handleWebhook("stripe", BODY, razorpayHeaders(hmacSha256Hex(BODY, WEBHOOK_SECRET), "evt_1"));

        assertEquals(404, response.getStatusCode().value());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void dispatcherFailureReleasesTheClaimAndReturns500SoTheGatewayRetries() throws Exception {
        // JAVA-003: a 200 here (the old behaviour) plus markProcessed lost a
        // paid renewal forever on any transient handler failure. The claim is
        // released and 500 returned; the redelivery re-claims and re-runs the
        // (row-idempotent) handlers.
        when(eventLedger.claim(anyString(), anyString(), any(), any(), anyString()))
            .thenReturn(BillingWebhookEventLedger.Claim.NEW);
        doThrow(new RuntimeException("handler blew up")).when(dispatcher).dispatch(any());

        ResponseEntity<Void> response =
            controller.handleWebhook("razorpay", BODY, razorpayHeaders(hmacSha256Hex(BODY, WEBHOOK_SECRET), "evt_1"));

        assertEquals(500, response.getStatusCode().value());
        verify(eventLedger).release("razorpay", BillingWebhookController.CONSUMER, "evt_1", BODY, "handler blew up");
        verify(eventLedger, org.mockito.Mockito.never()).markProcessed(anyString(), anyString(), any(), anyString());
    }

    /**
     * JAVA-004: PhonePe authenticates with {@code Authorization:
     * sha256(username:password)} - the controller used to bind only
     * X-Razorpay-Signature, so every PhonePe callback was a 401. Fixture
     * computed independently here with the scheme the verifier implements.
     */
    @Test
    void phonePeWebhookVerifiesViaTheAuthorizationHeader() throws Exception {
        PhonePeGatewayClient phonePe = new PhonePeGatewayClient(
            org.springframework.web.client.RestClient.builder(),
            "https://api-preprod.phonepe.com/apis/pg-sandbox", "https://api-preprod.phonepe.com/apis/pg-sandbox",
            "cid", "csecret", "1", "merchant", "hook_user", "hook_pass", "99900:MONTHLY", "199900:MONTHLY");
        BillingWebhookController phonePeController = new BillingWebhookController(
            new PaymentGatewayRegistry(List.of(razorpay, phonePe), "razorpay"), dispatcher, eventLedger);
        when(eventLedger.claim(anyString(), anyString(), any(), any(), anyString()))
            .thenReturn(BillingWebhookEventLedger.Claim.NEW);
        String body = "{\"event\":\"checkout.order.completed\",\"payload\":{\"subscriptionId\":\"sub_789\"}}";
        String authorization = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest("hook_user:hook_pass".getBytes(StandardCharsets.UTF_8)));

        org.springframework.http.HttpHeaders good = new org.springframework.http.HttpHeaders();
        good.add(org.springframework.http.HttpHeaders.AUTHORIZATION, authorization);
        assertEquals(200, phonePeController.handleWebhook("phonepe", body, good).getStatusCode().value());
        verify(dispatcher).dispatch(any());

        org.springframework.http.HttpHeaders wrong = new org.springframework.http.HttpHeaders();
        wrong.add(org.springframework.http.HttpHeaders.AUTHORIZATION, "0".repeat(64));
        // A Razorpay-style header must not satisfy PhonePe either.
        wrong.add("X-Razorpay-Signature", authorization);
        assertEquals(401, phonePeController.handleWebhook("phonepe", body, wrong).getStatusCode().value());
    }

    @Test
    void aClaimedDuplicateDeliveryReturns200WithoutDispatching() throws Exception {
        // V250 event-id dedupe: Razorpay redelivers until it sees a 2xx, and
        // network blips can deliver the same x-razorpay-event-id twice even
        // after a 200 - the second delivery must not re-run the handlers
        // (idempotent rows, yes; duplicate dunning emails, no).
        when(eventLedger.claim(anyString(), anyString(), any(), any(), anyString()))
            .thenReturn(BillingWebhookEventLedger.Claim.DUPLICATE);

        ResponseEntity<Void> response =
            controller.handleWebhook("razorpay", BODY, razorpayHeaders(hmacSha256Hex(BODY, WEBHOOK_SECRET), "evt_redelivered"));

        assertEquals(200, response.getStatusCode().value());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void aNewDeliveryIsClaimedThenMarkedProcessed() throws Exception {
        when(eventLedger.claim(anyString(), anyString(), any(), any(), anyString()))
            .thenReturn(BillingWebhookEventLedger.Claim.NEW);

        ResponseEntity<Void> response =
            controller.handleWebhook("razorpay", BODY, razorpayHeaders(hmacSha256Hex(BODY, WEBHOOK_SECRET), null));

        assertEquals(200, response.getStatusCode().value());
        // Null event id exercises the ledger's body-hash fallback - the claim
        // still happens, keyed on "sha256:<body>".
        verify(eventLedger).claim(org.mockito.ArgumentMatchers.eq("razorpay"),
            org.mockito.ArgumentMatchers.eq(BillingWebhookController.CONSUMER),
            isNull(), org.mockito.ArgumentMatchers.eq("subscription.charged"), org.mockito.ArgumentMatchers.eq(BODY));
        verify(eventLedger).markProcessed("razorpay", BillingWebhookController.CONSUMER, null, BODY);
    }

    /** Headers as Razorpay sends them; null values are simply absent, like a real request. */
    private static HttpHeaders razorpayHeaders(String signature, String eventId) {
        HttpHeaders headers = new HttpHeaders();
        if (signature != null) {
            headers.add("X-Razorpay-Signature", signature);
        }
        if (eventId != null) {
            headers.add("X-Razorpay-Event-Id", eventId);
        }
        return headers;
    }
}
