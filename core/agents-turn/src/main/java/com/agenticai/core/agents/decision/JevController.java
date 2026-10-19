package com.agenticai.core.agents.decision;

import com.agenticai.core.agents.decision.dto.JevCredentialActivationRequest;
import com.agenticai.core.agents.decision.dto.JevCredentialResponse;
import com.agenticai.core.agents.decision.dto.JevPlaygroundRequest;
import com.agenticai.core.agents.decision.dto.JevPolicyRequest;
import com.agenticai.core.agents.decision.dto.JevPolicyResponse;
import com.agenticai.core.agents.decision.dto.JevStatusResponse;
import com.agenticai.core.providers.capability.Capability;
import com.agenticai.core.providers.capability.CapabilityPolicyService;
import com.agenticai.core.providers.capability.ProviderCredentialService;
import com.agenticai.core.providers.capability.ProviderKeys;
import com.agenticai.core.security.TenantContext;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEPRECATED compat shim - the tenant-facing Jev surface ("bring your own
 * TypeSafe key, see that Jev is configured, and experiment with it") kept
 * byte-compatible for the {@code /jev} console page and existing e2e specs
 * while the real implementation moved onto the generic capability registry:
 *
 * <ul>
 *   <li>Credential ({@code /credential}) - activate/current/deactivate over the
 *   unified {@code provider_credential} table ({@code provider_key=TYPESAFE}),
 *   via {@link ProviderCredentialService}. Equivalent generic endpoint:
 *   {@code /api/capabilities/decision/credential}.
 *   <li>Status ({@code /status}) - answered by {@link DecisionEngine}: whose
 *   key resolves for THIS tenant (their own row, then the platform TYPESAFE
 *   credential, then the env bootstrap). Generic equivalent:
 *   {@code /api/capabilities/decision/status}.
 *   <li>Policy ({@code /policy}) - the tenant's DECISION-capability overrides
 *   on {@code capability_policy} via {@link CapabilityPolicyService}. Generic
 *   equivalent: {@code /api/capabilities/decision/policy}.
 *   <li>Playground ({@code /playground}) - bounded fail-visible pass-through
 *   through the engine's active {@link DecisionModel}. Generic equivalent:
 *   {@code /api/capabilities/decision/playground}.
 * </ul>
 *
 * <p>New consumers should use the {@code /api/capabilities/decision/*}
 * endpoints; this controller stays as long as the {@code /jev} page and
 * {@code e2e/tests-prod/jev-byok-live.spec.ts} do.
 */
@RestController
@RequestMapping("/api/jev")
public class JevController {

    private final ProviderCredentialService credentials;
    private final DecisionEngine decisions;
    private final CapabilityPolicyService policies;

    public JevController(
        ProviderCredentialService credentials,
        DecisionEngine decisions,
        CapabilityPolicyService policies
    ) {
        this.credentials = credentials;
        this.decisions = decisions;
        this.policies = policies;
    }

    @PostMapping("/credential")
    public ResponseEntity<JevCredentialResponse> activate(@Valid @RequestBody JevCredentialActivationRequest request) {
        requireTenant();
        return ResponseEntity.status(HttpStatus.CREATED).body(JevCredentialResponse.from(
            credentials.activate(ProviderKeys.TYPESAFE, request.apiKey(), null)));
    }

    @GetMapping("/credential/status")
    public ResponseEntity<JevCredentialResponse> credentialStatus() {
        return tenantCredential()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping("/credential")
    public ResponseEntity<Void> deactivate() {
        requireTenant();
        credentials.deactivate(ProviderKeys.TYPESAFE);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public ResponseEntity<JevStatusResponse> status() {
        return ResponseEntity.ok(new JevStatusResponse(
            decisions.isEnabled(),
            decisions.isAvailable(),
            decisions.keySource().orElse(null),
            tenantCredential().orElse(null)
        ));
    }

    /**
     * The tenant's decision policy - "control how the decision capability
     * behaves," not just whose key it spends. GET returns the raw nullable
     * overrides (all-null = fully inheriting platform defaults, whether a row
     * exists or not); PUT writes them. Both require a tenant context - a
     * tenant-less caller has no policy row to read or write (see {@link
     * #requireTenant}).
     */
    @GetMapping("/policy")
    public ResponseEntity<JevPolicyResponse> policy() {
        if (!hasTenant()) {
            return ResponseEntity.ok(JevPolicyResponse.inherit());
        }
        return ResponseEntity.ok(policies.current(Capability.DECISION)
            .map(p -> JevPolicyResponse.from(
                CapabilityPolicyService.decode(p.getOverrides()), p.getUpdatedAt()))
            .orElseGet(JevPolicyResponse::inherit));
    }

    @PutMapping("/policy")
    public ResponseEntity<JevPolicyResponse> updatePolicy(@Valid @RequestBody JevPolicyRequest request) {
        requireTenant();
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("costRouting", request.costRouting());
        overrides.put("agentRouting", request.agentRouting());
        overrides.put("simpleThreshold", request.simpleThreshold());
        var saved = policies.update(Capability.DECISION, overrides);
        return ResponseEntity.ok(JevPolicyResponse.from(
            CapabilityPolicyService.decode(saved.getOverrides()), saved.getUpdatedAt()));
    }

    @PostMapping("/playground")
    public ResponseEntity<Map<String, Object>> playground(@RequestBody JevPlaygroundRequest request) {
        DecisionModel.PlaygroundOutcome outcome = decisions
            .playground(request.state(), request.questions())
            .orElseThrow(() -> new JevNotConfiguredException(
                "Jev is not configured - add your TypeSafe key below, or ask a platform admin to set the platform TYPESAFE credential."));
        if (outcome.error() != null) {
            throw new JevUpstreamException(outcome.error());
        }
        return ResponseEntity.ok(Map.of(
            "model", outcome.model(),
            "keySource", outcome.keySource().name(),
            "answers", outcome.answers(),
            "usage", outcome.usage() == null ? Map.of() : outcome.usage()
        ));
    }

    // The credential + policy tables live in the TENANT schema, so every
    // repository touch must run under a tenant context. Tenant-less callers
    // exist by design - platform-admin JWTs never carry tenant_id
    // (TenantContextFilter) - and would otherwise fall through to the
    // public-schema connection where `provider_credential` does not exist,
    // surfacing as an opaque 500 instead of a clear answer.
    private boolean hasTenant() {
        return TenantContext.current().map(TenantContext::getTenantId).isPresent();
    }

    private Optional<JevCredentialResponse> tenantCredential() {
        if (!hasTenant()) {
            return Optional.empty();
        }
        return credentials.findByProviderKey(ProviderKeys.TYPESAFE).map(JevCredentialResponse::from);
    }

    private void requireTenant() {
        if (!hasTenant()) {
            throw new JevNotConfiguredException(
                "Jev keys are tenant-scoped - this session has no tenant, so there is no workspace keyring to manage.");
        }
    }
}
