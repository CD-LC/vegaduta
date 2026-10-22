package com.agenticai.core.billing.gateway;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST {@code /api/billing/webhook/{gateway}} — receives raw webhook events from payment gateways
 * (Razorpay, PhonePe, etc.), verifies signature, parses to {@link BillingEvent}, and dispatches to
 * handlers.
 *
 * <p>Status contract: 200 once the event was dispatched successfully (or is a dedupe-ledger
 * duplicate); 401 on a missing/invalid signature; 404 on an unknown gateway; <b>500 when parsing or
 * a handler fails</b> - the dedupe claim is released first, so the gateway's redelivery re-claims
 * and re-dispatches (JAVA-003). Dispatch is synchronous: nothing has been answered to the gateway
 * before the handlers ran. Handlers are row-level idempotent and the event-id ledger dedupes
 * completed deliveries, so a retry never double-applies.
 *
 * <p>Signature verification is gateway-specific and delegated to {@link
 * PaymentGatewayClient#verifyWebhookSignature(String, String)} with the value of the header the
 * gateway itself declares ({@link PaymentGatewayClient#webhookSignatureHeader()}), because each
 * gateway authenticates with its own header (Razorpay {@code X-Razorpay-Signature}, Stripe {@code
 * Stripe-Signature}, PhonePe {@code Authorization}; JAVA-004) - called explicitly BEFORE {@link
 * PaymentGatewayClient#parseWebhookEvent}, which does no verification of its own. Throws {@link
 * InvalidWebhookSignatureException} on mismatch.
 */
@RestController
@RequestMapping("/api/billing/webhook")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class BillingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookController.class);

    /** Dedupe consumer tag - see BillingWebhookEventLedger's "why per-consumer keys" note. */
    static final String CONSUMER = "billing-dispatch";

    private final PaymentGatewayRegistry gatewayRegistry;
    private final BillingEventDispatcher eventDispatcher;
    private final BillingWebhookEventLedger eventLedger;

    public BillingWebhookController(
            PaymentGatewayRegistry gatewayRegistry,
            BillingEventDispatcher eventDispatcher,
            BillingWebhookEventLedger eventLedger) {
        this.gatewayRegistry = gatewayRegistry;
        this.eventDispatcher = eventDispatcher;
        this.eventLedger = eventLedger;
    }

    /**
     * Webhook receiver - gateway-agnostic entry point.
     *
     * @param gateway "razorpay", "phonepe", etc. — resolved from registry
     * @param rawBody raw webhook JSON payload (signature verification is client-specific)
     * @param headers every request header; the gateway client declares which one carries its
     *     signature / event id
     * @return 200 on success/duplicate, 401 bad signature, 404 unknown gateway, 500 handler failure
     */
    @PostMapping("/{gateway}")
    public ResponseEntity<Void> handleWebhook(
            @PathVariable String gateway,
            @RequestBody String rawBody,
            // Every request header, case-insensitively. Which one carries the signature / event id
            // is the
            // GATEWAY's own declaration (PaymentGatewayClient#webhookSignatureHeader /
            // #webhookEventIdHeader),
            // so adding a gateway needs no change here. Gateways that send no event id still dedupe
            // on the
            // ledger's body-hash fallback (see BillingWebhookEventLedger).
            @RequestHeader HttpHeaders headers) {
        PaymentGatewayClient client;
        String eventId = null;
        try {
            // resolve() (not getClient()) - explicit gateway name, so an unknown
            // one throws rather than silently falling back to the default gateway.
            client = gatewayRegistry.resolve(gateway);
        } catch (IllegalArgumentException e) {
            log.warn("No client registered for gateway '{}'", gateway);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try {
            // Verify BEFORE parsing - parseWebhookEvent does no auth of its own,
            // it's pure JSON parsing. Skipping this would let anyone who knows
            // the URL POST a fake subscription.charged and get ACTIVE for free.
            // Each gateway declares its own signature header; its value (or null)
            // is handed over - the client fail-closes on a missing/incorrect
            // value, never an absent-header "skip".
            client.verifyWebhookSignature(
                    rawBody, headerValue(headers, client.webhookSignatureHeader()));

            BillingEvent event = client.parseWebhookEvent(rawBody);
            log.info(
                    "Parsed webhook event: type={}, subscriptionId={}, rawName={}",
                    event.type(),
                    event.gatewaySubscriptionId(),
                    event.rawEventName());

            // Event-id dedupe (V250): a redelivery of an already-processed
            // event is answered 200 without re-running the handlers - they are
            // row-level idempotent, but dedupe also silences the duplicate
            // dunning emails/receipts idempotency can't suppress.
            eventId = headerValue(headers, client.webhookEventIdHeader());
            if (eventLedger.claim(gateway, CONSUMER, eventId, event.rawEventName(), rawBody)
                    == BillingWebhookEventLedger.Claim.DUPLICATE) {
                return ResponseEntity.ok().build();
            }

            // Synchronous dispatch; a handler failure propagates to the
            // catch below (release + 500 -> gateway retry).
            eventDispatcher.dispatch(event);
            eventLedger.markProcessed(gateway, CONSUMER, eventId, rawBody);

            return ResponseEntity.ok().build();
        } catch (InvalidWebhookSignatureException e) {
            log.warn("Invalid webhook signature from gateway '{}': {}", gateway, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("Unexpected error handling webhook from gateway '{}'", gateway, e);
            // Release the dedupe claim (if one was made) so a redelivery of
            // this event re-claims and retries instead of being deduped away;
            // a no-op when the failure predates the claim. Never done on a
            // signature failure - a forged replay must not free a legit row.
            eventLedger.release(gateway, CONSUMER, eventId, rawBody, e.getMessage());
            // 5xx so the gateway redelivers; the released claim lets the
            // retry through and the handlers' idempotency keeps it safe.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * The first value of the named header, or {@code null} when the gateway declares none / the
     * request lacks it.
     */
    private static String headerValue(HttpHeaders headers, String name) {
        return (name == null || name.isBlank()) ? null : headers.getFirst(name);
    }
}
