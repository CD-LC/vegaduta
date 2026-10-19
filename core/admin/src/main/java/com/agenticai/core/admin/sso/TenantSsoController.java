package com.agenticai.core.admin.sso;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant admin's BYO-IdP surface (P2 item: productized per-tenant SSO).
 *
 * <ul>
 *   <li>{@code GET/PUT/DELETE /api/tenant/sso} - the IdP config itself,
 *       gated by {@code PERM_MANAGE_TENANT_SETTINGS} like every other
 *       tenant-wide settings surface
 *   <li>{@code POST/GET/DELETE /api/tenant/sso/scim-tokens} - bearer tokens
 *       for the SCIM-lite provisioning endpoint ({@link
 *       ScimProvisioningController}); the plaintext token is returned once
 *       at mint and only its hash is stored
 * </ul>
 */
// Gated with TenantSsoService: under app.identity.provider=auth0 no IdentityProviderAdminPort
// bean exists (SSO connection management is Keycloak-only), so this whole surface - including
// SCIM token minting, which lives here - is off. ScimProvisioningController itself stays active;
// it needs only IdentityUserAdminPort, which the Auth0 adapter supplies.
@RestController
@RequestMapping("/api/tenant/sso")
@Profile("security")
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "keycloak", matchIfMissing = true)
public class TenantSsoController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantSsoService ssoService;
    private final TenantScimTokenRepository tokenRepository;
    private final TenantLimits tenantLimits;

    public TenantSsoController(TenantSsoService ssoService, TenantScimTokenRepository tokenRepository,
        TenantLimits tenantLimits) {
        this.ssoService = ssoService;
        this.tokenRepository = tokenRepository;
        this.tenantLimits = tenantLimits;
    }

    @GetMapping
    public TenantSsoService.TenantSsoStatus status() {
        return ssoService.status(tenantSchema());
    }

    @PutMapping
    public TenantSsoService.TenantSsoStatus upsert(@Valid @RequestBody TenantSsoUpsertRequest request) {
        // Plan gate - BYO-IdP SSO is a paid-tier entitlement (plan_limits.sso_allowed).
        // Reads and DELETE stay open: seeing/tearing down existing config is always safe.
        String schema = tenantSchema();
        if (!tenantLimits.ssoAllowed(schema)) {
            throw new TenantLimitExceededException(
                "Your plan does not include SSO - upgrade your plan to configure a custom identity provider.");
        }
        ssoService.upsert(schema, request, subject());
        return ssoService.status(schema);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        ssoService.delete(tenantSchema());
        return ResponseEntity.noContent().build();
    }

    // --------------------------- SCIM tokens --------------------------------

    public record ScimTokenView(
        java.util.UUID id, String label, Instant createdAt, Instant lastUsedAt) {}

    @GetMapping("/scim-tokens")
    public List<ScimTokenView> listTokens() {
        return tokenRepository.findByTenantSchemaAndRevokedAtIsNull(tenantSchema()).stream()
            .map(t -> new ScimTokenView(t.getId(), t.getLabel(), t.getCreatedAt(), t.getLastUsedAt()))
            .toList();
    }

    /** Returns the plaintext token exactly once - only the SHA-256 hash is stored. */
    @PostMapping("/scim-tokens")
    public Map<String, Object> mintToken(@RequestBody(required = false) Map<String, String> body) {
        // Plan gate - minting a provisioning token is the SCIM entitlement's
        // entry point (plan_limits.scim_allowed); the runtime endpoints then
        // re-check the resolved tenant's plan on every call.
        String schema = tenantSchema();
        if (!tenantLimits.scimAllowed(schema)) {
            throw new TenantLimitExceededException(
                "Your plan does not include SCIM provisioning - upgrade your plan to mint provisioning tokens.");
        }
        String label = body == null ? null : body.get("label");
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "vdscim_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        TenantScimToken row = new TenantScimToken();
        row.setTenantSchema(tenantSchema());
        row.setTokenHash(ScimProvisioningController.sha256Hex(token));
        row.setLabel(label == null || label.isBlank() ? "SCIM token" : label.trim());
        row.setCreatedBy(subject());
        tokenRepository.save(row);
        return Map.of(
            "id", row.getId().toString(),
            "token", token,
            "label", row.getLabel());
    }

    @DeleteMapping("/scim-tokens/{id}")
    public ResponseEntity<Void> revokeToken(@PathVariable java.util.UUID id) {
        tokenRepository.findById(id)
            .filter(t -> t.getTenantSchema().equals(tenantSchema())) // same-tenant check - no cross-tenant revoke by id
            .ifPresent(t -> {
                t.setRevokedAt(Instant.now());
                tokenRepository.save(t);
            });
        return ResponseEntity.noContent().build();
    }

    private static String tenantSchema() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException("No tenant context"));
    }

    private static String subject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
    }
}
