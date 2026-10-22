package com.agenticai.core.platform.ops;

import com.agenticai.core.admin.AdminUserNotFoundException;
import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Session revocation for the platform console. Gated by the blanket
 * {@code /api/platform/** -> ROLE_platform-admin} rule.
 *
 * <ul>
 *   <li>{@code POST /api/platform/users/{keycloakUserId}/logout} -> {@code {ok:true}}.
 *       The id is the Keycloak user id (the {@code id} of a
 *       {@code GET /api/platform/users} row). Platform operators are refused
 *       with 403 (they are managed in the Keycloak console), which also covers
 *       an operator logging themself out.</li>
 *   <li>{@code POST /api/platform/tenants/{tenantId}/logout-all} ->
 *       {@code {usersLoggedOut}}. {@code tenantId} is the tenant UUID (roster
 *       link) or its schema name. Platform operators who happen to carry the
 *       tenant's {@code tenant_id} attribute are skipped, as is the caller.</li>
 * </ul>
 *
 * <p>Keycloak's logout revokes every SSO session and refresh token at once;
 * already-issued access tokens stay valid until they expire (minutes). Both
 * actions are written to the target tenant's audit log via
 * {@link PlatformTenantAuditor}.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform")
@Profile("security")
public class PlatformSessionController {

    private static final Logger log = LoggerFactory.getLogger(PlatformSessionController.class);
    private static final String PLATFORM_ADMIN_ROLE = "platform-admin";

    private final IdentityUserAdminPort keycloak;
    private final TenantRepository tenantRepository;
    private final PlatformTenantAuditor auditor;

    public PlatformSessionController(IdentityUserAdminPort keycloak, TenantRepository tenantRepository,
                                     PlatformTenantAuditor auditor) {
        this.keycloak = keycloak;
        this.tenantRepository = tenantRepository;
        this.auditor = auditor;
    }

    @PostMapping("/users/{keycloakUserId}/logout")
    public Map<String, Boolean> logoutUser(@PathVariable("keycloakUserId") String keycloakUserId) {
        KeycloakUserRepresentation target;
        try {
            target = keycloak.getUserById(keycloakUserId);
        } catch (RuntimeException e) {
            throw new AdminUserNotFoundException(keycloakUserId);
        }
        if (keycloakUserId.equals(actor())
            || keycloak.getUserRealmRoleNames(keycloakUserId).contains(PLATFORM_ADMIN_ROLE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Platform operators' sessions are managed in the Keycloak admin console, not through this API.");
        }
        keycloak.logoutUser(keycloakUserId);
        auditor.record(tenantOf(target), actor(), "platform.user.logout", "keycloak_user", keycloakUserId,
            "Revoked all sessions of user " + target.username());
        return Map.of("ok", true);
    }

    @PostMapping("/tenants/{tenantId}/logout-all")
    public Map<String, Integer> logoutTenant(@PathVariable("tenantId") String tenantId) {
        String schema = resolveSchema(tenantId);
        String self = actor();
        int loggedOut = 0;
        int failed = 0;
        for (KeycloakUserRepresentation user : keycloak.listUsersByTenant(schema)) {
            if (user.id() == null || user.id().equals(self)) {
                continue;
            }
            try {
                if (keycloak.getUserRealmRoleNames(user.id()).contains(PLATFORM_ADMIN_ROLE)) {
                    continue;
                }
                keycloak.logoutUser(user.id());
                loggedOut++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("logout-all: could not log out user {} of tenant {} ({})", user.id(), schema,
                    e.getClass().getSimpleName());
            }
        }
        auditor.record(schema, self, "platform.tenant.logout_all", "tenant", schema,
            "Revoked all sessions of " + loggedOut + " user(s)" + (failed > 0 ? "; " + failed + " failed" : ""));
        return Map.of("usersLoggedOut", loggedOut);
    }

    /** UUID -> that tenant's schema; otherwise must be an existing tenant's schema name. 404 when neither. */
    private String resolveSchema(String tenantId) {
        String value = tenantId == null ? "" : tenantId.trim();
        try {
            UUID id = UUID.fromString(value);
            return tenantRepository.findById(id).map(Tenant::getSchemaName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown tenant."));
        } catch (IllegalArgumentException notAUuid) {
            return tenantRepository.findBySchemaName(value).map(Tenant::getSchemaName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown tenant."));
        }
    }

    private static String actor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private static String tenantOf(KeycloakUserRepresentation u) {
        if (u.attributes() == null) {
            return null;
        }
        List<String> values = u.attributes().getOrDefault("tenant_id", List.of());
        return values.isEmpty() || values.get(0) == null || values.get(0).isBlank() ? null : values.get(0);
    }
}
