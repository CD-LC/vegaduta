package com.agenticai.core.payments.commerce;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.payments.commerce.CommerceDtos.CreateCartRequest;
import com.agenticai.core.payments.commerce.CommerceDtos.CreateIntentRequest;
import com.agenticai.core.payments.commerce.CommerceDtos.EventResponse;
import com.agenticai.core.payments.commerce.CommerceDtos.MandateResponse;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Service facade for {@link CommerceMandateController}: wraps {@link CommerceMandateService} and
 * maps {@link CommerceMandate} / event entities to response DTOs so the controller never handles
 * JPA entities. Transaction boundaries stay on {@link CommerceMandateService}'s own methods.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class CommerceMandateApiService {

    private final CommerceMandateService mandateService;
    private final CommerceMandateCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper = JsonMappers.plain();

    public CommerceMandateApiService(
            CommerceMandateService mandateService, CommerceMandateCanonicalizer canonicalizer) {
        this.mandateService = mandateService;
        this.canonicalizer = canonicalizer;
    }

    public MandateResponse createIntent(CreateIntentRequest body, String buyerSubject) {
        return toResponse(
                mandateService.createIntent(
                        body.agentId(),
                        buyerSubject,
                        body.intentText(),
                        body.categories(),
                        body.maxTotalMinor(),
                        body.currency(),
                        body.expiresAt()));
    }

    public MandateResponse createCart(UUID intentId, CreateCartRequest body) {
        return toResponse(
                mandateService.createCart(intentId, body.items(), body.payee(), body.expiresAt()));
    }

    public MandateResponse authorize(UUID cartId) {
        return toResponse(mandateService.authorize(cartId));
    }

    public MandateResponse capture(UUID cartId) {
        return toResponse(mandateService.capture(cartId));
    }

    public MandateResponse refund(UUID cartId, String reason) {
        return toResponse(mandateService.refund(cartId, reason));
    }

    /** Empty when no such mandate. */
    public Optional<MandateResponse> revoke(UUID id, String reason) {
        return Optional.ofNullable(mandateService.revoke(id, reason)).map(this::toResponse);
    }

    public List<MandateResponse> listForAgent(UUID agentId) {
        return mandateService.listForAgent(agentId).stream().map(this::toResponse).toList();
    }

    /** Empty when no such mandate. */
    public Optional<MandateResponse> find(UUID id) {
        return Optional.ofNullable(mandateService.findById(id)).map(this::toResponse);
    }

    public List<MandateResponse> cartsOf(UUID intentId) {
        return mandateService.cartsOf(intentId).stream().map(this::toResponse).toList();
    }

    /** The mandate's lifecycle trail, or empty when no such mandate. */
    public Optional<List<EventResponse>> events(UUID id) {
        if (mandateService.findById(id) == null) {
            return Optional.empty();
        }
        return Optional.of(
                mandateService.events(id).stream()
                        .map(
                                e ->
                                        new EventResponse(
                                                e.getId(),
                                                e.getEventType(),
                                                e.getActor(),
                                                e.getDetail(),
                                                e.getCreatedAt()))
                        .toList());
    }

    private MandateResponse toResponse(CommerceMandate m) {
        return new MandateResponse(
                m.getId(),
                m.getKind().name(),
                m.getIntentMandateId(),
                m.getAgentId(),
                m.getBuyerUserId(),
                m.getCurrency(),
                m.getMaxTotalMinor(),
                m.getCartTotalMinor(),
                readJson(m.getItemsJson()),
                m.getPayee(),
                readJson(m.getCategories()),
                m.getIntentText(),
                m.getStatus().name(),
                canonicalizer.canonicalHash(m.getCanonicalJson()),
                m.getKeyId(),
                mandateService.verifySignature(m.getId()),
                m.getBuyerAuthJson(),
                m.getAuthorizedAt(),
                m.getWalletDebitRef(),
                m.getExpiresAt(),
                m.getCreatedAt(),
                m.getRevokedAt(),
                m.getRevokeReason());
    }

    private Object readJson(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
