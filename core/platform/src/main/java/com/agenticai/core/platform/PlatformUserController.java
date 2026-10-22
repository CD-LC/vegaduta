package com.agenticai.core.platform;

import com.agenticai.core.admin.AdminUserNotFoundException;
import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.admin.keycloak.TenantAssignableRoles;
import com.agenticai.core.admin.permission.AdminPermissionAuthoritySource;
import com.agenticai.core.admin.permission.UserPermissionService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-tenant user administration for the platform operator - {@code /api/platform/users/**},
 * gated by the blanket {@code SecurityConfig} rule {@code /api/platform/** -> ROLE_platform-admin}.
 *
 * <p>Why this exists: the only user API before this was the tenant-scoped {@code /api/admin/users}
 * (needs {@code PERM_MANAGE_USERS}, which a tenant-less platform-admin token never carries), so an
 * operator had no way to find, suspend or repair a user without the Keycloak console. This is that
 * control, for the four things an operator actually needs: find a user across every tenant, see
 * their roles and permissions, suspend/restore sign-in, send a password-reset email, and correct a
 * tenant role.
 *
 * <p><b>Guards</b> (same intent as {@code UserAdminController}, tightened for a caller who spans
 * tenants):
 *
 * <ul>
 *   <li>Roles are bounded by {@link TenantAssignableRoles} - {@code platform-admin} can never be
 *       granted here, and a set without it can never be used to strip it, because a target who
 *       already holds it is refused outright (platform operators are managed in the Keycloak
 *       console).
 *   <li>No self-service: an operator cannot change their own roles or disable themselves through
 *       this surface.
 *   <li>A target with no {@code tenant_id} attribute is refused for mutation: there is no tenant
 *       schema to seed permissions or record audit into.
 * </ul>
 *
 * <p><b>Tenant context.</b> The caller is tenant-less, so its JPA calls resolve to the public
 * schema, where the per-tenant permission-grant and audit tables do not exist. Every tenant-data
 * call therefore runs inside {@link #asTenant}, which temporarily binds the TARGET user's tenant
 * and restores the caller's context afterwards. The audit row lands in the target tenant's own
 * audit log with the platform operator as the actor, so the tenant can see who touched their user.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/users")
@Profile("security")
public class PlatformUserController {

    private static final Logger log = LoggerFactory.getLogger(PlatformUserController.class);
    private static final String PLATFORM_ADMIN_ROLE = "platform-admin";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final IdentityUserAdminPort keycloak;
    private final UserPermissionService userPermissionService;
    private final AdminPermissionAuthoritySource permissionAuthoritySource;
    private final AuditLogService auditLogService;
    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;

    public PlatformUserController(
            IdentityUserAdminPort keycloak,
            UserPermissionService userPermissionService,
            AdminPermissionAuthoritySource permissionAuthoritySource,
            AuditLogService auditLogService,
            TenantRepository tenantRepository,
            AppUserRepository appUserRepository) {
        this.keycloak = keycloak;
        this.userPermissionService = userPermissionService;
        this.permissionAuthoritySource = permissionAuthoritySource;
        this.auditLogService = auditLogService;
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
    }

    // ----------------------------------------------------------------- reads

    /**
     * @param tenantId optional exact tenant filter - the tenant's UUID (what the platform roster
     *     links with) or its schema name (what Keycloak's {@code tenant_id} attribute holds); an
     *     unknown UUID matches no one
     * @param q optional case-insensitive substring over username, email and name
     * @param offset rows to skip (default 0); with {@code limit} this pages the matches. The response
     *     also carries {@code X-Total-Count} (same as the body {@code total}) and {@code X-Has-More}
     * @param limit page size, default 100, max 500 (the Keycloak client fetches the whole realm and
     *     filters in memory - see its own javadoc - so this bounds the response, not the upstream
     *     cost)
     */
    @GetMapping
    public ResponseEntity<UserListResponse> list(
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(name = "offset", required = false) Long offset) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String tenantFilter = resolveTenantFilter(tenantId);
        RowContext context = rowContext();
        List<UserRow> matched =
                keycloak.listUsers().stream()
                        .filter(u -> tenantFilter == null || tenantFilter.equals(tenantOf(u)))
                        .filter(u -> needle.isEmpty() || matches(u, needle))
                        .sorted(
                                Comparator.comparing(
                                        u -> String.valueOf(u.username()).toLowerCase(Locale.ROOT)))
                        .map(u -> row(u, context))
                        .toList();
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        long skip = ListPaging.offset(offset);
        List<UserRow> page = matched.stream().skip(skip).limit(cap).toList();
        long seen = skip + page.size();
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(matched.size()))
                .header("X-Has-More", String.valueOf(matched.size() > seen))
                .body(new UserListResponse(matched.size(), page));
    }

    @GetMapping("/{userId}")
    public UserDetail get(@PathVariable("userId") String userId) {
        return detail(requireUser(userId));
    }

    // ------------------------------------------------------------- mutations

    @PutMapping("/{userId}/enabled")
    public UserDetail setEnabled(
            @PathVariable("userId") String userId, @RequestBody EnabledRequest body) {
        if (body == null || body.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        KeycloakUserRepresentation target = requireMutableTarget(userId, "enable/disable");
        keycloak.setUserEnabled(userId, body.enabled());
        audit(
                target,
                body.enabled() ? "platform.user.enable" : "platform.user.disable",
                (body.enabled() ? "Enabled" : "Disabled") + " user " + target.username());
        return detail(keycloak.getUserById(userId));
    }

    /** Emails the user a Keycloak update-password link; the operator never sees a credential. */
    @PostMapping("/{userId}/password-reset-email")
    public ActionResult sendPasswordReset(@PathVariable("userId") String userId) {
        KeycloakUserRepresentation target =
                requireMutableTarget(userId, "send a password reset to");
        if (target.email() == null || target.email().isBlank()) {
            throw new IllegalArgumentException(
                    "This user has no email address, so a reset link cannot be sent.");
        }
        keycloak.sendActionsEmail(userId, List.of("UPDATE_PASSWORD"));
        audit(
                target,
                "platform.user.password_reset_email",
                "Password-reset email sent to " + target.email());
        return new ActionResult(true, "Password-reset email sent to " + target.email() + ".");
    }

    /**
     * Full-set replace of the target's realm roles, bounded by {@link TenantAssignableRoles}.
     * Reseeds the new roles' default permission bundle additively (never claws back a permission an
     * admin hand-picked), exactly as the tenant-side controller does.
     */
    @PutMapping("/{userId}/roles")
    public UserDetail setRoles(
            @PathVariable("userId") String userId, @RequestBody RolesRequest body) {
        if (body == null || body.roles() == null) {
            throw new IllegalArgumentException("roles is required");
        }
        KeycloakUserRepresentation target = requireMutableTarget(userId, "change the roles of");
        try {
            TenantAssignableRoles.requireAllAssignable(body.roles());
        } catch (IllegalArgumentException e) {
            audit(
                    target,
                    "platform.user.set_roles",
                    AuditOutcome.FAILURE,
                    "Rejected non-assignable role request " + body.roles() + ": " + e.getMessage());
            throw e;
        }
        String tenant = tenantOf(target);
        keycloak.replaceUserRealmRoles(userId, body.roles());
        asTenant(
                tenant,
                () -> {
                    userPermissionService.seedMissingDefaultPermissions(
                            tenant, userId, body.roles(), actor());
                    permissionAuthoritySource.evict(tenant, userId);
                    return null;
                });
        audit(target, "platform.user.set_roles", "Set roles to " + body.roles());
        return detail(target);
    }

    // --------------------------------------------------------------- helpers

    private KeycloakUserRepresentation requireUser(String userId) {
        try {
            return keycloak.getUserById(userId);
        } catch (RuntimeException e) {
            throw new AdminUserNotFoundException(userId);
        }
    }

    /** Resolves the target and applies the guards every mutation shares. */
    private KeycloakUserRepresentation requireMutableTarget(String userId, String verb) {
        if (userId.equals(actor())) {
            throw new IllegalArgumentException("You cannot " + verb + " your own account here.");
        }
        KeycloakUserRepresentation target = requireUser(userId);
        if (keycloak.getUserRealmRoleNames(userId).contains(PLATFORM_ADMIN_ROLE)) {
            throw new IllegalArgumentException(
                    "Platform operators are managed in the Keycloak admin console, not through this"
                            + " API.");
        }
        if (tenantOf(target) == null) {
            throw new IllegalArgumentException(
                    "This user has no tenant_id attribute, so there is no tenant to apply the"
                            + " change in. Repair the account in Keycloak first.");
        }
        return target;
    }

    private UserDetail detail(KeycloakUserRepresentation user) {
        String tenant = tenantOf(user);
        List<String> roles = keycloak.getUserRealmRoleNames(user.id());
        List<String> permissions =
                tenant == null
                        ? List.of()
                        : asTenant(
                                tenant,
                                () ->
                                        List.copyOf(
                                                userPermissionService.getGrantedPermissions(
                                                        tenant, user.id())));
        return new UserDetail(
                row(user, rowContext()),
                roles,
                permissions,
                user.requiredActions() == null ? List.of() : user.requiredActions());
    }

    /**
     * Runs {@code work} with the target tenant bound, then restores whatever context the caller
     * had.
     */
    private <T> T asTenant(String tenantId, Supplier<T> work) {
        Optional<TenantContext> previous = TenantContext.current();
        try {
            TenantContext.set(
                    TenantContext.of(
                            tenantId,
                            actor(),
                            previous.map(TenantContext::getRoles).orElse(Set.of())));
            return work.get();
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }
    }

    private void audit(KeycloakUserRepresentation target, String action, String detail) {
        audit(target, action, AuditOutcome.SUCCESS, detail);
    }

    /**
     * Written into the target tenant's audit log. A failure to write must not turn an
     * already-applied change into an error the operator would retry, so it is logged loudly instead
     * - the structured log line below is the fallback record.
     */
    private void audit(
            KeycloakUserRepresentation target, String action, AuditOutcome outcome, String detail) {
        String tenant = tenantOf(target);
        log.info(
                "platform-user-audit actor={} action={} target={} tenant={} outcome={} detail={}",
                actor(),
                action,
                target.id(),
                tenant,
                outcome,
                detail);
        if (tenant == null) {
            return;
        }
        try {
            asTenant(
                    tenant,
                    () ->
                            auditLogService.record(
                                    actor(),
                                    action,
                                    "keycloak_user",
                                    target.id(),
                                    outcome,
                                    detail));
        } catch (RuntimeException e) {
            log.error(
                    "Could not write tenant audit row for {} on user {} (tenant {})",
                    action,
                    target.id(),
                    tenant,
                    e);
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
        return values.isEmpty() || values.get(0) == null || values.get(0).isBlank()
                ? null
                : values.get(0);
    }

    private static boolean matches(KeycloakUserRepresentation u, String needle) {
        return List.of(nz(u.username()), nz(u.email()), nz(u.firstName()), nz(u.lastName()))
                .stream()
                .anyMatch(s -> s.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * A tenant filter value as the schema name Keycloak stores. A UUID-shaped value is looked up as
     * a tenant id; when no tenant has that id it is still tried as a schema name verbatim (a schema
     * could in principle be UUID-shaped), which then simply matches nobody.
     */
    private String resolveTenantFilter(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        String value = tenantId.trim();
        try {
            UUID id = UUID.fromString(value);
            return tenantRepository.findById(id).map(Tenant::getSchemaName).orElse(value);
        } catch (IllegalArgumentException notAUuid) {
            return value;
        }
    }

    /**
     * Request-wide lookups for the row decorations, each loaded once. A failing lookup degrades to
     * "unknown" (null tenantName / lastSeenAt) rather than failing the list.
     */
    private RowContext rowContext() {
        Map<String, String> tenantNames = new HashMap<>();
        try {
            for (Tenant t : tenantRepository.findAll()) {
                tenantNames.put(t.getSchemaName(), t.getName());
            }
        } catch (RuntimeException e) {
            log.warn("Platform users: tenant name lookup failed", e);
        }
        Map<String, Instant> lastSeen = new HashMap<>();
        try {
            for (AppUserRepository.KeycloakUserLastSeen r : appUserRepository.findAllLastSeen()) {
                lastSeen.put(r.getKeycloakUserId(), r.getLastSeenAt());
            }
        } catch (RuntimeException e) {
            log.warn("Platform users: last-seen lookup failed", e);
        }
        return new RowContext(tenantNames, lastSeen);
    }

    private record RowContext(Map<String, String> tenantNames, Map<String, Instant> lastSeen) {}

    private static UserRow row(KeycloakUserRepresentation u, RowContext context) {
        String tenant = tenantOf(u);
        return new UserRow(
                u.id(),
                u.username(),
                u.email(),
                u.firstName(),
                u.lastName(),
                Boolean.TRUE.equals(u.enabled()),
                Boolean.TRUE.equals(u.emailVerified()),
                tenant,
                u.createdTimestamp() == null ? null : Instant.ofEpochMilli(u.createdTimestamp()),
                context.lastSeen().get(u.id()),
                tenant == null ? null : context.tenantNames().get(tenant));
    }

    // ----------------------------------------------------------------- shapes

    /**
     * {@code tenantId} is the tenant SCHEMA name (Keycloak's {@code tenant_id} attribute), as
     * before. {@code createdAt} is Keycloak's creation time, {@code lastSeenAt} the V475 {@code
     * app_user.last_seen_at} (null = not seen since tracking began, or no app_user row), {@code
     * tenantName} the tenant's display name (null when unknown).
     */
    public record UserRow(
            String id,
            String username,
            String email,
            String firstName,
            String lastName,
            boolean enabled,
            boolean emailVerified,
            String tenantId,
            Instant createdAt,
            Instant lastSeenAt,
            String tenantName) {}

    /** {@code total} is the match count before the {@code limit}/{@code offset} window. */
    public record UserListResponse(int total, List<UserRow> users) {}

    public record UserDetail(
            UserRow user,
            List<String> roles,
            List<String> permissions,
            List<String> requiredActions) {}

    public record EnabledRequest(Boolean enabled) {}

    public record RolesRequest(Set<String> roles) {}

    public record ActionResult(boolean ok, String message) {}
}
