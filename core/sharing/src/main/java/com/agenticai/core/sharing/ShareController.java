package com.agenticai.core.sharing;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-facing grant management for both resource types, mounted at two concrete paths - {@code
 * /api/agents/{id}/grants} and {@code /api/workflows/{id}/grants} - via one {@code
 * {resourceKind:agents|workflows}} path variable, matching this codebase's per-resource-type
 * controller convention ({@code AgentShareOwnerController} is mounted at {@code
 * /api/agents/{agentId}/share}) while keeping the actual grant/revoke/list logic in exactly one
 * place ({@link ResourceAccessService}) rather than two near-duplicate controller classes.
 *
 * <p>Auth: falls through to {@code SecurityConfig}'s default {@code anyRequest().authenticated()} -
 * no {@code permitAll()} rule needed or added; that pattern is reserved for the external,
 * unauthenticated agent-share feature and doesn't apply here (every caller of this controller must
 * already be a signed-in tenant member).
 *
 * <p><b>Resolving a resource's owner without depending on core/agents or core/workflow:</b> see
 * {@link ResourceOwnerLookup}'s javadoc. As of this module's initial build, no implementation is
 * registered for either resource type (neither {@code Agent} nor {@code WorkflowDefinition} has a
 * {@code createdBy} column yet), so {@code createdBy} resolves to {@code null} for every resource -
 * {@link ResourceAccessService} treats that as "legacy resource", which makes grant/revoke/list
 * admin-only until the next workstream backfills real ownership.
 *
 * <p><b>Why {@code @Profile("security")}:</b> this controller injects {@link IdentityUserAdminPort}
 * directly, and that bean is itself {@code @Profile("security")} because its four {@code
 * app.keycloak.*} {@code @Value} properties exist only in {@code application-security.yml}. As an
 * unconditional {@code @RestController} it therefore hard-required a profile-gated bean, which made
 * the entire application context unloadable under any profile set omitting {@code security} -
 * including the CI smoke test. Being absent alongside Keycloak is the correct behaviour rather than
 * a degradation: every grant resolves a real Keycloak user, so sharing cannot function without it.
 * This matches how {@code TenantProvisioningService} and {@code TenantDataErasureService} are
 * already gated.
 */
@RestController
@Profile("security")
@RequestMapping({
    "/api/{resourceKind:agents|workflows}/{resourceId}/grants",
    "/api/knowledge/{resourceKind:collections}/{resourceId}/grants"
})
public class ShareController {

    private final ResourceAccessService accessService;
    private final IdentityUserAdminPort keycloakAdminClient;
    private final ShareRateLimiter rateLimiter;
    private final Map<ResourceType, ResourceOwnerLookup> ownerLookups;

    public ShareController(
            ResourceAccessService accessService,
            IdentityUserAdminPort keycloakAdminClient,
            ShareRateLimiter rateLimiter,
            List<ResourceOwnerLookup> ownerLookups) {
        this.accessService = accessService;
        this.keycloakAdminClient = keycloakAdminClient;
        this.rateLimiter = rateLimiter;
        this.ownerLookups =
                ownerLookups.stream()
                        .collect(
                                Collectors.toMap(
                                        ResourceOwnerLookup::supportedType, lookup -> lookup));
    }

    public record GrantRequest(
            @NotBlank @Email String granteeEmail, @NotNull PermissionLevel permissionLevel) {}

    public record GrantResponse(
            UUID id,
            String granteeUserId,
            String granteeEmail,
            PermissionLevel permissionLevel,
            String grantedBy,
            Instant grantedAt) {}

    /**
     * Mint or update a grant. Resolves {@code granteeEmail} to a Keycloak user id and confirms
     * same-tenant membership itself (reusing {@code IdentityUserAdminPort}, same pattern as {@code
     * UserAdminController}) so an invalid recipient fails fast with a generic {@link
     * InvalidGranteeException} (403) before ever calling {@link ResourceAccessService#grant} -
     * which re-checks the same thing as a defense-in-depth backstop, not because the
     * controller-level check is expected to be bypassed.
     *
     * <p><b>Abuse control:</b> this endpoint's email -&gt; user-id resolution is a potential
     * user-enumeration oracle across a large tenant (repeated calls with guessed emails could map
     * out tenant membership by timing/ response-shape, even though the response body itself is
     * deliberately generic on failure), and grant-spam (flooding one resource with grants) is a
     * separate abuse shape entirely. Both are capped by {@link
     * ShareRateLimiter#assertGrantWriteAllowed} - the platform's standard Redis {@code INCR} +
     * conditional {@code EXPIRE} fixed-window limiter (fail-open on Redis errors, per-caller {@code
     * tenantId:subject} key), checked BEFORE the Keycloak lookup so an over-limit caller never
     * exercises the oracle at all. Over the cap is a 429 ({@link ShareRateLimitExceededException}).
     */
    @PostMapping
    public ResponseEntity<GrantResponse> grant(
            @PathVariable String resourceKind,
            @PathVariable UUID resourceId,
            @Valid @RequestBody GrantRequest request) {
        rateLimiter.assertGrantWriteAllowed();
        ResourceType type = resourceTypeOf(resourceKind);
        String tenantId = requireTenantId();
        KeycloakUserRepresentation grantee =
                keycloakAdminClient
                        .findByEmail(request.granteeEmail())
                        .filter(
                                user ->
                                        user.attributes() != null
                                                && user.attributes()
                                                        .getOrDefault("tenant_id", List.of())
                                                        .contains(tenantId))
                        .orElseThrow(InvalidGranteeException::new);

        String createdBy = resolveCreatedBy(type, resourceId);
        ResourceGrant grant =
                accessService.grant(
                        type, resourceId, createdBy, grantee.id(), request.permissionLevel());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(grant, grantee.email()));
    }

    /**
     * Owner-only (or tenant-admin) - who currently has access to this resource. Read-capped per
     * caller ({@link ShareRateLimiter#assertGrantReadAllowed}).
     */
    @GetMapping
    public List<GrantResponse> list(
            @PathVariable String resourceKind, @PathVariable UUID resourceId) {
        rateLimiter.assertGrantReadAllowed();
        ResourceType type = resourceTypeOf(resourceKind);
        String createdBy = resolveCreatedBy(type, resourceId);
        return accessService.listGrants(type, resourceId, createdBy).stream()
                .map(g -> toResponse(g, resolveEmailBestEffort(g.getGranteeUserId())))
                .toList();
    }

    @DeleteMapping("/{grantId}")
    public ResponseEntity<Void> revoke(
            @PathVariable String resourceKind,
            @PathVariable UUID resourceId,
            @PathVariable UUID grantId) {
        ResourceType type = resourceTypeOf(resourceKind);
        String createdBy = resolveCreatedBy(type, resourceId);
        accessService.revoke(type, resourceId, createdBy, grantId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------

    private static ResourceType resourceTypeOf(String resourceKind) {
        // The {resourceKind:agents|workflows} path-variable regex already
        // guarantees one of exactly these two values reaches this method -
        // this switch has no other case to fall through to.
        return switch (resourceKind) {
            case "agents" -> ResourceType.AGENT;
            case "workflows" -> ResourceType.WORKFLOW;
            case "collections" -> ResourceType.KNOWLEDGE_COLLECTION;
            default ->
                    throw new IllegalStateException(
                            "Unreachable - path patterns restrict resourceKind to"
                                    + " agents|workflows|collections");
        };
    }

    private String resolveCreatedBy(ResourceType type, UUID resourceId) {
        ResourceOwnerLookup lookup = ownerLookups.get(type);
        if (lookup == null) {
            return null;
        }
        return lookup.findCreatedBy(resourceId).orElse(null);
    }

    private String resolveEmailBestEffort(String granteeUserId) {
        try {
            return keycloakAdminClient.getUserById(granteeUserId).email();
        } catch (RuntimeException e) {
            // The grantee's Keycloak account may have been deleted since the
            // grant was made - the grant row itself is still valid history,
            // just shown without an email in that edge case.
            return null;
        }
    }

    private static String requireTenantId() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No TenantContext for an authenticated grants request -"
                                                + " SecurityConfig should have blocked this"));
    }

    private static GrantResponse toResponse(ResourceGrant grant, String granteeEmail) {
        return new GrantResponse(
                grant.getId(),
                grant.getGranteeUserId(),
                granteeEmail,
                grant.getPermissionLevel(),
                grant.getGrantedBy(),
                grant.getGrantedAt());
    }
}
