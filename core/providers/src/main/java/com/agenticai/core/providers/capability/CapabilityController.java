package com.agenticai.core.providers.capability;

import com.agenticai.core.security.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The generic capability surface - the registry's tenant-facing half. Where a
 * brand-specific provider gets onboarded, the tenant sees one uniform set of
 * endpoints rather than a per-vendor controller ({@code SarvamController},
 * {@code JevController}, {@code E2bCredentialController} all repeat this same
 * activate/status/deactivate shape).
 *
 * <p>Three concerns, all provider-agnostic:
 *
 * <p><b>Catalog</b> ({@code GET /api/capabilities}) - which capabilities the
 * platform knows, which providers can serve each, and (tenant context
 * permitting) whether a credential already exists for each provider. This is
 * the data-driven list a generic console page renders - a newly onboarded
 * provider appears here the moment its {@link ProviderCredentialValidator}
 * bean exists, no frontend edit needed.
 *
 * <p><b>Credential</b> ({@code /{capability}/credential}) - tenant BYOK over
 * the unified {@code provider_credential} table: validate-before-persist via
 * the provider's {@link ProviderCredentialValidator}, encrypt at rest,
 * status-only reads, deactivate-not-delete.
 *
 * <p><b>Policy</b> ({@code /{capability}/policy}) - the tenant's nullable
 * overrides for how the capability may act, stored as free-form jsonb in
 * {@code capability_policy} and validated by the capability's {@link
 * CapabilityPolicyValidator} bean (no validator = the capability has no
 * tenant-tunable policy = writes rejected).
 *
 * <p>Capability-specific behavior - a status payload richer than "is a
 * credential stored," or a playground - lives in a per-capability controller
 * under the same path root ({@code DecisionCapabilityController} owns
 * {@code /api/capabilities/decision/status} and {@code /playground}); this
 * controller never learns what a decision model is.
 *
 * <p>The {@code provider_credential} and {@code capability_policy} tables
 * live in the TENANT schema, so every repository touch must run under a
 * tenant context - the same structural reason {@code JevController}
 * documents; tenant-less callers (platform-admin JWTs carry no tenant_id) get
 * clean "no tenant" answers instead of a public-schema miss.
 */
@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final ProviderCredentialService credentials;
    private final CapabilityPolicyService policies;
    private final Map<String, CapabilityPolicyValidator> policyValidators;

    public CapabilityController(
        ProviderCredentialService credentials,
        CapabilityPolicyService policies,
        List<CapabilityPolicyValidator> policyValidators
    ) {
        this.credentials = credentials;
        this.policies = policies;
        this.policyValidators = policyValidators == null ? Map.of() : policyValidators.stream()
            .collect(java.util.stream.Collectors.toMap(CapabilityPolicyValidator::capability, v -> v));
    }

    /**
     * The registry catalog - one entry per {@link Capability}, each listing
     * its registered providers and (under a tenant context) the stored
     * credential's status. Rendering rule for the console: {@code status ==
     * ACTIVE} means usable, anything else (INACTIVE/INVALID/null) means "not
     * configured" - one signal, no second badge to disagree with it.
     */
    @GetMapping
    public List<CapabilityDescriptor> catalog() {
        return java.util.Arrays.stream(Capability.values())
            .map(this::describe)
            .toList();
    }

    @PostMapping("/{capability}/credential")
    public ResponseEntity<ProviderCredentialResponse> activate(
        @PathVariable String capability,
        @Valid @RequestBody CapabilityCredentialRequest request
    ) {
        CapabilityProviderTarget target = resolveProvider(capability, request.provider());
        requireTenant();
        return ResponseEntity.status(HttpStatus.CREATED).body(ProviderCredentialResponse.from(
            credentials.activate(target.providerKey(), request.apiKey(), null)));
    }

    @GetMapping("/{capability}/credential/status")
    public ResponseEntity<ProviderCredentialResponse> credentialStatus(
        @PathVariable String capability,
        @RequestParam(required = false) String provider
    ) {
        CapabilityProviderTarget target = resolveProvider(capability, provider);
        if (!hasTenant()) {
            return ResponseEntity.noContent().build();
        }
        return credentials.findByProviderKey(target.providerKey())
            .map(ProviderCredentialResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping("/{capability}/credential")
    public ResponseEntity<Void> deactivate(
        @PathVariable String capability,
        @RequestParam(required = false) String provider
    ) {
        CapabilityProviderTarget target = resolveProvider(capability, provider);
        requireTenant();
        credentials.deactivate(target.providerKey());
        return ResponseEntity.noContent().build();
    }

    /**
     * The tenant's policy for the capability: raw nullable overrides (empty
     * map = fully inheriting platform defaults, whether a row exists or not).
     * PUT replaces the whole map - omit a key to reset it to inherit.
     */
    @GetMapping("/{capability}/policy")
    public ResponseEntity<CapabilityPolicyResponse> policy(@PathVariable String capability) {
        Capability cap = Capability.fromKey(capability);
        if (!hasTenant()) {
            return ResponseEntity.ok(CapabilityPolicyResponse.inherit(cap));
        }
        return ResponseEntity.ok(policies.current(cap)
            .map(p -> CapabilityPolicyResponse.of(cap, p))
            .orElseGet(() -> CapabilityPolicyResponse.inherit(cap)));
    }

    @PutMapping("/{capability}/policy")
    public ResponseEntity<CapabilityPolicyResponse> updatePolicy(
        @PathVariable String capability,
        @RequestBody Map<String, Object> overrides
    ) {
        Capability cap = Capability.fromKey(capability);
        requireTenant();
        CapabilityPolicyValidator validator = policyValidators.get(cap.key());
        if (validator == null) {
            throw new UnknownCapabilityException(
                "Capability '" + cap.key() + "' has no tenant policy surface - nothing registered to validate it.");
        }
        validator.validateOverrides(overrides);
        return ResponseEntity.ok(CapabilityPolicyResponse.of(cap, policies.update(cap, overrides)));
    }

    /**
     * Which provider a credential call targets: the explicitly requested one
     * when given (it must actually serve this capability), else the
     * capability's sole registered provider. Zero registered providers means
     * the capability exists in the enum but nothing serves it yet; more than
     * one without an explicit choice is a 400, not a coin flip.
     */
    private CapabilityProviderTarget resolveProvider(String capabilityKey, String requested) {
        Capability cap = Capability.fromKey(capabilityKey);
        List<ProviderCredentialValidator> serving = credentials.validators().values().stream()
            .filter(v -> v.capabilities().contains(cap.name()))
            .toList();
        if (requested != null && !requested.isBlank()) {
            ProviderCredentialValidator validator = credentials.validators().get(requested.trim());
            if (validator == null || !validator.capabilities().contains(cap.name())) {
                throw new UnknownCapabilityException(
                    "Provider '" + requested + "' does not serve capability '" + cap.key() + "'");
            }
            return new CapabilityProviderTarget(cap, validator.providerKey());
        }
        if (serving.isEmpty()) {
            throw new UnknownCapabilityException(
                "Capability '" + cap.key() + "' has no registered providers");
        }
        if (serving.size() > 1) {
            throw new UnknownCapabilityException(
                "Capability '" + cap.key() + "' is served by " + serving.size()
                    + " providers - the request must name one via 'provider'");
        }
        return new CapabilityProviderTarget(cap, serving.get(0).providerKey());
    }

    private record CapabilityProviderTarget(Capability capability, String providerKey) {
    }

    /** One catalog row per provider under a capability - {@code credentialStatus} is null without a tenant context (the table is tenant-schema). */
    public record ProviderDescriptor(
        String providerKey,
        String displayName,
        ProviderCredentialResponse credential
    ) {
    }

    public record CapabilityDescriptor(
        String key,
        List<ProviderDescriptor> providers
    ) {
    }

    private CapabilityDescriptor describe(Capability cap) {
        boolean tenant = hasTenant();
        List<ProviderDescriptor> providers = credentials.validators().values().stream()
            .filter(v -> v.capabilities().contains(cap.name()))
            .map(v -> new ProviderDescriptor(
                v.providerKey(),
                v.displayName(),
                tenant
                    ? credentials.findByProviderKey(v.providerKey())
                        .map(ProviderCredentialResponse::from)
                        .orElse(null)
                    : null))
            .toList();
        return new CapabilityDescriptor(cap.key(), providers);
    }

    private boolean hasTenant() {
        return TenantContext.current().map(TenantContext::getTenantId).isPresent();
    }

    private void requireTenant() {
        if (!hasTenant()) {
            throw new CapabilityNotConfiguredException(
                "Capability credentials are tenant-scoped - this session has no tenant, so there is no workspace keyring to manage.");
        }
    }
}
