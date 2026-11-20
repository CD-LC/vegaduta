package com.agenticai.core.payments.commerce;

import com.agenticai.core.payments.commerce.CommerceDtos.CreateCartRequest;
import com.agenticai.core.payments.commerce.CommerceDtos.CreateIntentRequest;
import com.agenticai.core.payments.commerce.CommerceDtos.EventResponse;
import com.agenticai.core.payments.commerce.CommerceDtos.MandateResponse;
import com.agenticai.core.payments.commerce.CommerceDtos.RevokeRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-facing REST for the AP2-style mandate chain (agentic commerce): {@code POST /intent}
 * grants an agent a signed shopping ceiling; {@code POST /{intentId}/cart} builds a concrete spend
 * under it; {@code /authorize} is the buyer's signature; {@code /capture} settles it from the
 * agent's wallet; {@code /refund} and {@code /revoke} close the loop. Every row is
 * tenant-schema-scoped automatically; all transitions land in {@code commerce_mandate_event}.
 *
 * <p>{@code buyerUserId} is NEVER caller-supplied: the buyer is the authenticated JWT subject from
 * {@link TenantContext} - a client cannot authorize a spend "as" someone else.
 */
@RestController
@RequestMapping("/api/commerce/mandates")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class CommerceMandateController {

    private final CommerceMandateApiService mandateService;

    public CommerceMandateController(CommerceMandateApiService mandateService) {
        this.mandateService = mandateService;
    }

    @PostMapping("/intent")
    public ResponseEntity<MandateResponse> createIntent(
            @Valid @RequestBody CreateIntentRequest body) {
        try {
            MandateResponse intent = mandateService.createIntent(body, currentSubject());
            return ResponseEntity.status(HttpStatus.CREATED).body(intent);
        } catch (CommerceMandateException e) {
            throw mandateError(e);
        }
    }

    @PostMapping("/{intentId}/cart")
    public ResponseEntity<MandateResponse> createCart(
            @PathVariable UUID intentId, @Valid @RequestBody CreateCartRequest body) {
        try {
            MandateResponse cart = mandateService.createCart(intentId, body);
            return ResponseEntity.status(HttpStatus.CREATED).body(cart);
        } catch (CommerceMandateException e) {
            throw mandateError(e);
        }
    }

    /** The buyer signs: turns PENDING_AUTHORIZATION into AUTHORIZED. */
    @PostMapping("/{cartId}/authorize")
    public MandateResponse authorize(@PathVariable UUID cartId) {
        try {
            return mandateService.authorize(cartId);
        } catch (CommerceMandateException e) {
            throw mandateError(e);
        }
    }

    /** Settle an authorized cart from the agent's wallet. */
    @PostMapping("/{cartId}/capture")
    public MandateResponse capture(@PathVariable UUID cartId) {
        try {
            return mandateService.capture(cartId);
        } catch (CommerceMandateException e) {
            throw mandateError(e);
        }
    }

    @PostMapping("/{cartId}/refund")
    public MandateResponse refund(
            @PathVariable UUID cartId, @Valid @RequestBody(required = false) RevokeRequest body) {
        try {
            return mandateService.refund(cartId, body == null ? null : body.reason());
        } catch (CommerceMandateException e) {
            throw mandateError(e);
        }
    }

    @PostMapping("/{id}/revoke")
    public MandateResponse revoke(
            @PathVariable UUID id, @Valid @RequestBody(required = false) RevokeRequest body) {
        return mandateService
                .revoke(id, body == null ? null : body.reason())
                .orElseThrow(CommerceMandateController::noSuchMandate);
    }

    @GetMapping
    public List<MandateResponse> list(@RequestParam UUID agentId) {
        return mandateService.listForAgent(agentId);
    }

    @GetMapping("/{id}")
    public MandateResponse get(@PathVariable UUID id) {
        return mandateService.find(id).orElseThrow(CommerceMandateController::noSuchMandate);
    }

    @GetMapping("/{intentId}/carts")
    public List<MandateResponse> carts(@PathVariable UUID intentId) {
        return mandateService.cartsOf(intentId);
    }

    /** The mandate's append-only lifecycle trail - the dispute evidence. */
    @GetMapping("/{id}/events")
    public List<EventResponse> events(@PathVariable UUID id) {
        return mandateService.events(id).orElseThrow(CommerceMandateController::noSuchMandate);
    }

    // ---------------------------------------------------------------- internals

    private static ResponseStatusException noSuchMandate() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such mandate");
    }

    private String currentSubject() {
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "no authenticated subject to act as");
        }
        return subject;
    }

    private ResponseStatusException mandateError(CommerceMandateException e) {
        HttpStatus status =
                switch (e.getCode()) {
                    case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "NOT_THE_BUYER" -> HttpStatus.FORBIDDEN;
                    case "OVER_CEILING",
                            "CAPTURE_FAILED",
                            "WRONG_STATE",
                            "INTENT_INACTIVE",
                            "CART_EXPIRED",
                            "INTENT_EXPIRED" ->
                            HttpStatus.CONFLICT;
                    case "BAD_SIGNATURE", "BAD_BUYER_AUTH" -> HttpStatus.UNPROCESSABLE_ENTITY;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return new ResponseStatusException(status, e.getCode() + ": " + e.getMessage());
    }
}
