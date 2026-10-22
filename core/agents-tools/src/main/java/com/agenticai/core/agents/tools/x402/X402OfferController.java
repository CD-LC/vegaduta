package com.agenticai.core.agents.tools.x402;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agentspi.AgentWalletLedger;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
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
 * The tenant-facing management surface for x402 offers - an agent "publishes a paid endpoint" here.
 * EDITOR access on the agent is required: attaching a price and a seller wallet is as privileged as
 * editing the agent.
 *
 * <p>The seller wallet is resolved (or created) in the caller's tenant schema at publish time; its
 * id is stored on the {@code control_plane.x402_offer} row so the public settle path can credit it
 * without re-deriving.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/x402/offers")
public class X402OfferController {

    private final X402OfferRepository offerRepository;
    private final AgentWalletLedger walletService;
    private final AgentService agentService;

    public X402OfferController(
            X402OfferRepository offerRepository,
            ObjectProvider<AgentWalletLedger> walletLedger,
            AgentService agentService) {
        this.offerRepository = offerRepository;
        // Absent when the payments module is not wired (BILLING_PAYMENTS off): offers needing a wallet
        // then answer a "payments disabled" refusal instead of the application failing to start.
        this.walletService = PaymentsDisabledAgentWalletLedger.orDisabled(walletLedger);
        this.agentService = agentService;
    }

    public record CreateOfferRequest(
            @NotBlank
                    @Pattern(
                            regexp = "[a-z0-9][a-z0-9-]{0,62}",
                            message = "slug must be lowercase URL-safe")
                    String slug,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @Positive long priceMinor,
            @NotBlank @Size(min = 3, max = 3) String currency,
            /** Internal settlement target; null = auto-create the agent's wallet. */
            UUID walletId,
            // Optional EVM 'exact' scheme - all-or-nothing set.
            @Positive Long evmPriceAtomic,
            @Size(max = 64) String evmAsset,
            @Size(max = 32) String evmNetwork,
            @Size(max = 64) String evmAssetName,
            @Size(max = 16) String evmAssetVersion,
            @Size(max = 256) String payTo,
            /** The JSON payload a paid caller receives - the resource itself. */
            @Size(max = 65536) String resourcePayload) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable UUID agentId, @Valid @RequestBody CreateOfferRequest body) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        String tenant =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "no tenant context"));
        if (offerRepository.findBySlug(body.slug()).isPresent()) {
            // Disabled slugs stay taken too - re-using a retired URL invites
            // confusion with the old listing's payments.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "slug is taken");
        }
        AgentWalletLedger.WalletRef wallet =
                body.walletId() != null
                        ? walletService
                                .findById(body.walletId())
                                .filter(w -> w.agentId().equals(agentId))
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                                        "walletId is not a wallet of this agent"))
                        : walletService.getOrCreate(agentId, body.currency());
        if (!wallet.currency().equalsIgnoreCase(body.currency())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet currency "
                            + wallet.currency()
                            + " != offer currency "
                            + body.currency());
        }
        boolean evmAllOrNothing = body.evmPriceAtomic() != null;
        if (evmAllOrNothing
                && (body.evmAsset() == null
                        || body.evmNetwork() == null
                        || body.evmAssetName() == null
                        || body.evmAssetVersion() == null
                        || body.payTo() == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "EVM offers need evmPriceAtomic + evmAsset + evmNetwork + evmAssetName + evmAssetVersion + payTo");
        }
        X402Offer offer = new X402Offer();
        offer.setId(UUID.randomUUID());
        offer.setSlug(body.slug());
        offer.setTenantId(tenant);
        offer.setAgentId(agentId);
        offer.setWalletId(wallet.id());
        offer.setTitle(body.title());
        offer.setDescription(body.description());
        offer.setPriceMinor(body.priceMinor());
        offer.setCurrency(body.currency().toUpperCase(java.util.Locale.ROOT));
        offer.setEvmPriceAtomic(body.evmPriceAtomic());
        offer.setEvmAsset(body.evmAsset());
        offer.setEvmNetwork(body.evmNetwork());
        offer.setEvmAssetName(body.evmAssetName());
        offer.setEvmAssetVersion(body.evmAssetVersion());
        offer.setPayTo(body.payTo());
        offer.setResourcePayload(body.resourcePayload());
        X402Offer saved = offerRepository.save(offer);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable UUID agentId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return ListPaging.window(
            offerRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(this::toResponse)
                .toList(),
            limit, offset, 50, 500);
    }

    @PostMapping("/{offerId}/disable")
    public Map<String, Object> disable(@PathVariable UUID agentId, @PathVariable UUID offerId) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
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
