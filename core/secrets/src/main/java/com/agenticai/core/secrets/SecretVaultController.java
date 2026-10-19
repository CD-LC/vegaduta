package com.agenticai.core.secrets;

import com.agenticai.core.secrets.dto.SecretResponse;
import com.agenticai.core.secrets.dto.SecretStoreRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * Tenant-facing vault API.
 *
 * <p><b>Write-only by design.</b> There is no endpoint that returns a stored
 * value, because there is no legitimate caller for one: the UI needs to show
 * that a secret exists (it gets {@link SecretResponse}, preview only) and a
 * tool needs the value (it gets it in-process through
 * {@link SecretReferenceResolver}, never over HTTP). Adding a "reveal" button
 * later would mean adding the one endpoint an attacker with a stolen session
 * most wants, in exchange for a convenience that rotation already covers.
 *
 * <p>Every method takes {@link Authentication} rather than a user id from the
 * request, so USER-scoped secrets are bound to the token's subject and cannot
 * be addressed on someone else's behalf.
 */
@RestController
@RequestMapping("/api/secrets")
public class SecretVaultController {

    // Granular RBAC (2026-09-23): TENANT-scope writes track the integrations
    // permission - a vault entry is what an integration authenticates WITH,
    // so the same scope that activates providers governs its secrets. A
    // tenant-admin with MANAGE_INTEGRATIONS revoked can no longer write
    // shared secrets either (grants bind everyone). platform-admin keeps the
    // role check since it carries no tenant grants by design.
    private static final String PERM_MANAGE_INTEGRATIONS = "PERM_MANAGE_INTEGRATIONS";
    private static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";

    private final SecretVaultService vaultService;

    public SecretVaultController(SecretVaultService vaultService) {
        this.vaultService = vaultService;
    }

    /** Everything the caller may see: all shared secrets plus their own personal ones. */
    @GetMapping
    public List<SecretResponse> list(Authentication authentication) {
        return vaultService.listVisibleTo(userId(authentication)).stream()
            .map(SecretResponse::from)
            .toList();
    }

    /**
     * Stores a new secret or rotates an existing one of the same name and
     * scope. Returns 201 either way - the caller's intent is "this name now
     * has this value", and distinguishing create from rotate in the status
     * code would tell an attacker probing names which ones already exist.
     */
    @PostMapping
    public ResponseEntity<SecretResponse> store(
        @Valid @RequestBody SecretStoreRequest request,
        Authentication authentication
    ) {
        String actor = userId(authentication);
        requireCanWriteScope(request.scope(), authentication);
        TenantSecret stored = vaultService.store(
            request.name(),
            request.scope(),
            null,
            request.kind(),
            request.description(),
            request.value(),
            request.expiresAt(),
            Boolean.TRUE.equals(request.codeToolAccess()),
            request.allowedHosts(),
            actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(SecretResponse.from(stored));
    }

    /** Body of {@code PUT /api/secrets/{id}/allowed-hosts}. */
    public record AllowedHostsRequest(@Size(max = 32) List<@Size(max = 255) String> allowedHosts) {}

    /**
     * Replaces a secret's destination allow-list (SEC-006). An empty list
     * means the secret is never sent to any URL-bound destination. Same write
     * gate as revoke: TENANT scope needs the integrations permission.
     */
    @PutMapping("/{id}/allowed-hosts")
    public SecretResponse updateAllowedHosts(
        @PathVariable UUID id,
        @Valid @RequestBody AllowedHostsRequest request,
        Authentication authentication
    ) {
        TenantSecret updated = vaultService.updateAllowedHosts(
            id, request.allowedHosts() == null ? List.of() : request.allowedHosts(),
            userId(authentication), isWorkspaceAdmin(authentication));
        return SecretResponse.from(updated);
    }

    /**
     * Revokes by default; {@code ?purge=true} deletes irreversibly.
     *
     * <p>Revoke is the default because it is the safe one: it stops the secret
     * working immediately while keeping the row that proves what used it and
     * when. Purge exists for a real erasure request and is opt-in.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @PathVariable UUID id,
        @RequestParam(name = "purge", defaultValue = "false") boolean purge,
        Authentication authentication
    ) {
        String actor = userId(authentication);
        // The scope lives on the row, not in the request, so the gate has to be
        // applied inside the service where the row is already loaded - doing it
        // here would need a second read and a second "is it visible to you"
        // check that could disagree with the first.
        boolean admin = isWorkspaceAdmin(authentication);
        if (purge) {
            vaultService.purge(id, actor, admin);
        } else {
            vaultService.revoke(id, actor, admin);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * A TENANT-scoped secret is shared by everyone in the workspace, so writing
     * one is workspace configuration and carries the same posture as the
     * integrations scope ({@code PERM_MANAGE_INTEGRATIONS}; granular RBAC
     * 2026-09-23 - previously the blanket tenant-admin role). A USER-scoped
     * secret is the caller's own and needs no special right.
     *
     * <p>This cannot be expressed in {@code SecurityConfig}: the scope arrives
     * in the request body, and a path-matcher rule cannot see it. That is why
     * the check lives here rather than alongside the other route rules.
     */
    private void requireCanWriteScope(SecretScope scope, Authentication authentication) {
        if (scope == SecretScope.TENANT && !isWorkspaceAdmin(authentication)) {
            throw new SecretWriteForbiddenException();
        }
    }

    /**
     * Authorities are the literal Keycloak role strings the rest of this app
     * matches on (see {@code SecurityConfig}'s {@code hasAuthority(
     * "ROLE_tenant-admin")} rules), not Spring's {@code hasRole} short form.
     *
     * <p>Returns false when unauthenticated, which is the safe direction: under
     * {@code NoSecurityConfig} (local dev, no Keycloak) every caller is the same
     * pseudo-user, and letting that pseudo-user write shared secrets would make
     * the dev experience diverge from production in exactly the place where
     * that divergence is most likely to hide a bug.
     */
    private boolean isWorkspaceAdmin(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(a -> PERM_MANAGE_INTEGRATIONS.equals(a) || ROLE_PLATFORM_ADMIN.equals(a));
    }

    /**
     * The subject the token was issued for. Null-tolerant because
     * {@code NoSecurityConfig} runs in local dev with no authentication at
     * all, and an NPE there would make the vault untestable outside a full
     * Keycloak stack. In that mode every caller is the same pseudo-user, which
     * is correct: there are no separate identities to keep apart.
     */
    private String userId(Authentication authentication) {
        return authentication != null ? authentication.getName() : "anonymous";
    }
}
