package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agenticai.core.billing.gateway.BillingWebhookEventLedger;
import com.agenticai.core.billing.gateway.InvalidWebhookSignatureException;
import com.agenticai.core.billing.gateway.RazorpayGatewayClient;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * B9-PR4 revenue-path contract - the signature gate on {@link
 * RazorpayWebhookController}, the FIRST of the two webhook entry points
 * (the other, {@code /api/billing/webhook/{gateway}}, is covered by {@code
 * BillingWebhookControllerContractTest} in core/billing). {@code
 * RazorpayWebhookServiceTest} deliberately skipped the controller as "a
 * two-line pass-through with no branch logic" - true for event handling,
 * but the pass-through IS the auth boundary: the one property that keeps
 * anyone who knows the URL from confirming their own upgrade with a forged
 * {@code subscription.activated}. This suite pins exactly that property:
 * the service is reachable through a valid HMAC and through nothing else.
 *
 * <p>Uses a REAL {@link RazorpayGatewayClient} (dummy credentials - its
 * constructor makes no network call, see {@code RazorpayGatewayClientTest}'s
 * javadoc) with the signature computed independently in this test via
 * HMAC-SHA256, so the test pins Razorpay's documented algorithm (hex
 * HMAC-SHA256 of the exact raw body, keyed with the webhook secret) rather
 * than mocking the verifier and asserting a tautology.
 */
class RazorpayWebhookControllerSignatureTest {

    private static final String WEBHOOK_SECRET = "test_webhook_secret";
    private static final String BODY =
        "{\"event\":\"subscription.activated\",\"payload\":{\"subscription\":{\"entity\":{\"id\":\"sub_123\"}}}}";

    private final RazorpayGatewayClient gatewayClient =
        new RazorpayGatewayClient("rzp_test_dummy_key_id", "dummy_key_secret", WEBHOOK_SECRET);
    private final RazorpayWebhookService webhookService = mock(RazorpayWebhookService.class);
    private final RazorpayWebhookController controller =
        new RazorpayWebhookController(gatewayClient, webhookService, mock(BillingWebhookEventLedger.class));

    /** Computed here, not via the Razorpay SDK, so the test is an independent pin of the algorithm. */
    private static String hmacSha256Hex(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validSignatureReturns200AndHandsTheExactRawBodyToTheService() throws Exception {
        ResponseEntity<Void> response = controller.handleWebhook(BODY, hmacSha256Hex(BODY, WEBHOOK_SECRET), "evt_test_1");

        assertEquals(200, response.getStatusCode().value());
        // The EXACT raw body string - re-serialization before verification
        // would break the HMAC (signatures cover bytes, not JSON semantics),
        // so the controller must pass it through untouched.
        verify(webhookService).handleVerifiedEvent(BODY);
    }

    @Test
    void tamperedBodyWithAnOtherwiseValidSignatureNeverReachesTheService() throws Exception {
        // The core replay attack: capture one legitimate delivery, swap the
        // body to point the event at a different subscription/tenant.
        String signatureOfOriginal = hmacSha256Hex(BODY, WEBHOOK_SECRET);
        String tampered = BODY.replace("sub_123", "sub_attacker");

        assertThrows(InvalidWebhookSignatureException.class,
            () -> controller.handleWebhook(tampered, signatureOfOriginal, "evt_test_1"));
        verifyNoInteractions(webhookService);
    }

    @Test
    void signatureMintedWithTheWrongSecretIsRejected() throws Exception {
        String forged = hmacSha256Hex(BODY, "not_the_configured_secret");

        assertThrows(InvalidWebhookSignatureException.class,
            () -> controller.handleWebhook(BODY, forged, "evt_test_1"));
        verifyNoInteractions(webhookService);
    }

    @Test
    void missingSignatureHeaderNeverReachesTheService() {
        // The header is declared required=false, so a header-less POST does
        // reach the controller with null. Today the Razorpay SDK NPEs on a
        // null signature (razorpay-java 1.4.9's verifySignature calls
        // expectedSignature.getBytes() with no null check), and
        // RazorpayGatewayClient only wraps RazorpayException - so what
        // escapes is a bare RuntimeException, NOT the 403-mapped
        // InvalidWebhookSignatureException. Asserting the supertype keeps
        // this test green if that wart is ever hardened into the proper
        // exception; the load-bearing property either way is that the
        // service is never invoked.
        assertThrows(RuntimeException.class, () -> controller.handleWebhook(BODY, null, null));
        verifyNoInteractions(webhookService);
    }

    @Test
    void emptyAndGarbageSignatureHeadersAreRejected() {
        assertThrows(InvalidWebhookSignatureException.class,
            () -> controller.handleWebhook(BODY, "", null));
        assertThrows(InvalidWebhookSignatureException.class,
            () -> controller.handleWebhook(BODY, "deadbeef-not-a-signature", null));
        verifyNoInteractions(webhookService);
    }
}
