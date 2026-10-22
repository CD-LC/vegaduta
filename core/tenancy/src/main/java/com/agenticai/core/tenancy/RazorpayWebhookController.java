package com.agenticai.core.tenancy;

import com.agenticai.core.billing.gateway.BillingWebhookEventLedger;
import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #811 - Razorpay's own webhook target for subscription lifecycle events. Public/unauthenticated by
 * design (there is no tenant JWT on an incoming gateway callback) - authenticity instead comes
 * entirely from {@link RazorpayGatewayClient#verifyWebhookSignature}, same posture {@code
 * SecurityConfig}'s existing {@code /webhook/** -> permitAll} rule already applies to every other
 * inbound channel webhook (Twilio, Meta, Slack, Discord, Telegram) in this codebase - see any of
 * those controllers' own header comments for the same reasoning.
 *
 * <p>Always returns 200 once the signature checks out, regardless of what {@link
 * RazorpayWebhookService#handleVerifiedEvent} did with the event (ignored it, applied it) -
 * Razorpay retries non-2xx deliveries, and "this event type/subscription isn't one we act on" is a
 * normal, expected outcome, not a delivery failure worth a retry storm over.
 */
@RestController
@RequestMapping("/webhook/razorpay")
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class RazorpayWebhookController {

    // #825 Multi-gateway follow-up - deliberately the CONCRETE RazorpayGatewayClient type, not the
    // PaymentGatewayClient interface: this webhook URL (/webhook/razorpay) is inherently
    // Razorpay-specific, and depending on the interface stopped being unambiguous the moment a
    // second PaymentGatewayClient bean (PhonePeGatewayClient) was registered - see
    // PaymentGatewayRegistry's javadoc for the "which callers need the registry vs. a concrete
    // type" reasoning this follows.
    /** Dedupe consumer tag - see BillingWebhookEventLedger's "why per-consumer keys" note. */
    static final String CONSUMER = "tenant-state";

    // Razorpay's client through the gateway-neutral interface, resolved by name
    // from the registry (ADR-0011 rule a) - injecting PaymentGatewayClient
    // directly is ambiguous once PhonePe is a second gateway bean.
    private final PaymentGatewayClient paymentGatewayClient;
    private final RazorpayWebhookService razorpayWebhookService;
    private final BillingWebhookEventLedger eventLedger;

    @Autowired
    public RazorpayWebhookController(
            PaymentGatewayRegistry gatewayRegistry,
            RazorpayWebhookService razorpayWebhookService,
            BillingWebhookEventLedger eventLedger) {
        this(
                gatewayRegistry.resolve(RazorpayWebhookService.GATEWAY),
                razorpayWebhookService,
                eventLedger);
    }

    RazorpayWebhookController(
            PaymentGatewayClient paymentGatewayClient,
            RazorpayWebhookService razorpayWebhookService,
            BillingWebhookEventLedger eventLedger) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.razorpayWebhookService = razorpayWebhookService;
        this.eventLedger = eventLedger;
    }

    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signatureHeader,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        // Throws InvalidWebhookSignatureException on a bad/missing signature -
        // already mapped to 403 by GlobalExceptionHandler, so a failed
        // verification never reaches handleVerifiedEvent below.
        paymentGatewayClient.verifyWebhookSignature(rawBody, signatureHeader);
        // Event-id dedupe (V250) - a redelivery of an already-applied event is
        // answered 200 without re-running the tenant state machine.
        if (eventLedger.claim("razorpay", CONSUMER, eventId, null, rawBody)
                == BillingWebhookEventLedger.Claim.DUPLICATE) {
            return ResponseEntity.ok().build();
        }
        try {
            razorpayWebhookService.handleVerifiedEvent(rawBody);
        } catch (RuntimeException e) {
            // Release the claim so Razorpay's redelivery retries the work
            // instead of being deduped away (a propagated failure would 500,
            // which is exactly what triggers that redelivery).
            eventLedger.release("razorpay", CONSUMER, eventId, rawBody, e.getMessage());
            throw e;
        }
        eventLedger.markProcessed("razorpay", CONSUMER, eventId, rawBody);
        return ResponseEntity.ok().build();
    }
}
