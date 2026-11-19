package com.agenticai.core.agents.tools.x402;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agentspi.AgentWalletLedger;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    private final X402OfferService offerService;
    private final AgentService agentService;

    @Autowired
    public X402OfferController(X402OfferService offerService, AgentService agentService) {
        this.offerService = offerService;
        this.agentService = agentService;
    }

    /**
     * Legacy wiring kept for callers outside this module (agents-studio test); delete once they
     * build the service themselves.
     */
    @Deprecated
    public X402OfferController(
            X402OfferRepository offerRepository,
            ObjectProvider<AgentWalletLedger> walletLedger,
            AgentService agentService) {
        this(new X402OfferService(offerRepository, walletLedger), agentService);
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        offerService.create(
                                agentId,
                                new X402OfferService.OfferCommand(
                                        body.slug(),
                                        body.title(),
                                        body.description(),
                                        body.priceMinor(),
                                        body.currency(),
                                        body.walletId(),
                                        body.evmPriceAtomic(),
                                        body.evmAsset(),
                                        body.evmNetwork(),
                                        body.evmAssetName(),
                                        body.evmAssetVersion(),
                                        body.payTo(),
                                        body.resourcePayload())));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @PathVariable UUID agentId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return ListPaging.window(offerService.list(agentId), limit, offset, 50, 500);
    }

    @PostMapping("/{offerId}/disable")
    public Map<String, Object> disable(@PathVariable UUID agentId, @PathVariable UUID offerId) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        return offerService.disable(agentId, offerId);
    }
}
