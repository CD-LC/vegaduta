package com.agenticai.core.agents.decision;

import com.agenticai.core.agents.decision.dto.DecisionPlaygroundRequest;
import com.agenticai.core.agents.decision.dto.DecisionStatusResponse;
import com.agenticai.core.providers.capability.CapabilityNotConfiguredException;
import com.agenticai.core.providers.capability.CapabilityUpstreamException;
import com.agenticai.core.providers.capability.ProviderCredentialResponse;
import com.agenticai.core.providers.capability.ProviderCredentialService;
import com.agenticai.core.security.TenantContext;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The DECISION capability's own slice of the generic {@code
 * /api/capabilities} surface - the parts {@code CapabilityController}
 * deliberately doesn't know: what "configured" means beyond a stored
 * credential (a provider whose key actually resolves), and a bounded
 * fail-visible playground for experimenting with the resolved key.
 * Credential lifecycle and policy live on the generic {@code
 * /api/capabilities/decision/credential|policy} endpoints; this controller
 * adds only what is specific to DECISION, and even that is answered through
 * {@link DecisionEngine} so it never names a vendor.
 *
 * <p>{@code /api/jev/status|playground} remain as a compat shim over the same
 * engine - this is the endpoint new consumers and a future generic console
 * page should use.
 */
@RestController
@RequestMapping("/api/capabilities/decision")
public class DecisionCapabilityController {

    private final DecisionEngine decisions;
    private final ProviderCredentialService credentials;

    public DecisionCapabilityController(DecisionEngine decisions, ProviderCredentialService credentials) {
        this.decisions = decisions;
        this.credentials = credentials;
    }

    /**
     * Whose key funds decision calls for this tenant right now: the active
     * provider's resolved source (TENANT/PLATFORM/ENV) plus the tenant's own
     * stored credential when one exists. Same contract {@code
     * /api/jev/status} exposes, with the provider named explicitly.
     */
    @GetMapping("/status")
    public ResponseEntity<DecisionStatusResponse> status() {
        return ResponseEntity.ok(new DecisionStatusResponse(
            decisions.isEnabled(),
            decisions.isAvailable(),
            decisions.active().map(DecisionModel::providerKey).orElse(null),
            decisions.keySource().orElse(null),
            tenantCredential().orElse(null)
        ));
    }

    /**
     * Bounded pass-through to the active decision model - {@code state} plus
     * named typed questions - so a tenant can verify their key works without
     * ever seeing it. Unlike the internal decision paths (fail-open
     * heuristics), a failure surfaces as a real error: experimenting requires
     * knowing whether the key actually worked.
     */
    @PostMapping("/playground")
    public ResponseEntity<Map<String, Object>> playground(@RequestBody DecisionPlaygroundRequest request) {
        DecisionModel.PlaygroundOutcome outcome = decisions
            .playground(request.state(), request.questions())
            .orElseThrow(() -> new CapabilityNotConfiguredException(
                "No decision provider is configured - add a key via POST /api/capabilities/decision/credential, or ask a platform admin to set the platform credential."));
        if (outcome.error() != null) {
            throw new CapabilityUpstreamException(outcome.error());
        }
        return ResponseEntity.ok(Map.of(
            "model", outcome.model(),
            "keySource", outcome.keySource().name(),
            "answers", outcome.answers(),
            "usage", outcome.usage() == null ? Map.of() : outcome.usage()
        ));
    }

    /** The tenant's stored credential for whichever provider would serve - null without a tenant context (the table is tenant-schema). */
    private Optional<ProviderCredentialResponse> tenantCredential() {
        boolean hasTenant = TenantContext.current().map(TenantContext::getTenantId).isPresent();
        if (!hasTenant) {
            return Optional.empty();
        }
        return decisions.active()
            .flatMap(m -> credentials.findByProviderKey(m.providerKey()))
            .map(ProviderCredentialResponse::from);
    }
}
