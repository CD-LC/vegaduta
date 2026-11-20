package com.agenticai.core.agents.tools.x402;

import com.agenticai.core.agentspi.AgentWalletLedger;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Offer management behind {@link X402OfferController}; owns repository access and transactions. */
@Service
public class X402OfferService {

    private final X402OfferRepository offerRepository;
    private final AgentWalletLedger walletService;

    public X402OfferService(
            X402OfferRepository offerRepository, ObjectProvider<AgentWalletLedger> walletLedger) {
        this.offerRepository = offerRepository;
        // Absent when the payments module is not wired (BILLING_PAYMENTS off).
        this.walletService = PaymentsDisabledAgentWalletLedger.orDisabled(walletLedger);
    }

    public record OfferCommand(
            String slug,
            String title,
            String description,
            long priceMinor,
            String currency,
            UUID walletId,
            Long evmPriceAtomic,
            String evmAsset,
            String evmNetwork,
            String evmAssetName,
            String evmAssetVersion,
            String payTo,
            String resourcePayload) {}

    @Transactional
    public Map<String, Object> create(UUID agentId, OfferCommand cmd) {
        String tenant =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "no tenant context"));
        if (offerRepository.findBySlug(cmd.slug()).isPresent()) {
            // Disabled slugs stay taken too - re-using a retired URL invites
            // confusion with the old listing's payments.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "slug is taken");
        }
        AgentWalletLedger.WalletRef wallet =
                cmd.walletId() != null
                        ? walletService
                                .findById(cmd.walletId())
                                .filter(w -> w.agentId().equals(agentId))
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                                        "walletId is not a wallet of this agent"))
                        : walletService.getOrCreate(agentId, cmd.currency());
        if (!wallet.currency().equalsIgnoreCase(cmd.currency())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet currency "
                            + wallet.currency()
                            + " != offer currency "
                            + cmd.currency());
        }
        boolean evmAllOrNothing = cmd.evmPriceAtomic() != null;
        if (evmAllOrNothing
                && (cmd.evmAsset() == null
                        || cmd.evmNetwork() == null
                        || cmd.evmAssetName() == null
                        || cmd.evmAssetVersion() == null
                        || cmd.payTo() == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "EVM offers need evmPriceAtomic + evmAsset + evmNetwork + evmAssetName + evmAssetVersion + payTo");
        }
        X402Offer offer = new X402Offer();
        offer.setId(UUID.randomUUID());
        offer.setSlug(cmd.slug());
        offer.setTenantId(tenant);
        offer.setAgentId(agentId);
        offer.setWalletId(wallet.id());
        offer.setTitle(cmd.title());
        offer.setDescription(cmd.description());
        offer.setPriceMinor(cmd.priceMinor());
        offer.setCurrency(cmd.currency().toUpperCase(java.util.Locale.ROOT));
        offer.setEvmPriceAtomic(cmd.evmPriceAtomic());
        offer.setEvmAsset(cmd.evmAsset());
        offer.setEvmNetwork(cmd.evmNetwork());
        offer.setEvmAssetName(cmd.evmAssetName());
        offer.setEvmAssetVersion(cmd.evmAssetVersion());
        offer.setPayTo(cmd.payTo());
        offer.setResourcePayload(cmd.resourcePayload());
        X402Offer saved = offerRepository.save(offer);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID agentId) {
        return offerRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public Map<String, Object> disable(UUID agentId, UUID offerId) {
        X402Offer offer =
                offerRepository
                        .findById(offerId)
                        .filter(o -> o.getAgentId().equals(agentId))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "no such offer"));
        offer.setStatus(X402Offer.Status.DISABLED);
        return toResponse(offerRepository.save(offer));
    }

    private Map<String, Object> toResponse(X402Offer o) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", o.getId().toString());
        m.put("slug", o.getSlug());
        m.put("url", "/x402/" + o.getSlug());
        m.put("agentId", o.getAgentId().toString());
        m.put("walletId", o.getWalletId().toString());
        m.put("title", o.getTitle());
        m.put("priceMinor", o.getPriceMinor());
        m.put("currency", o.getCurrency());
        m.put("evm", o.hasEvmRequirement());
        m.put("status", o.getStatus().name());
        m.put("createdAt", o.getCreatedAt().toString());
        return m;
    }
}
