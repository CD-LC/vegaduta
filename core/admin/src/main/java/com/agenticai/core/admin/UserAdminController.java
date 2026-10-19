package com.agenticai.core.admin;

import com.agenticai.core.admin.dto.AdminUserResponse;
import com.agenticai.core.admin.dto.CreateUserRequest;
import com.agenticai.core.admin.dto.SetUserEnabledRequest;
import com.agenticai.core.admin.dto.SetUserPermissionsRequest;
import com.agenticai.core.admin.dto.SetUserRolesRequest;
import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.admin.keycloak.TenantAssignableRoles;
import com.agenticai.core.admin.permission.AdminPermissionAuthoritySource;
import com.agenticai.core.admin.permission.Permission;
import com.agenticai.core.admin.permission.TenantAssignablePermissions;
import com.agenticai.core.admin.permission.UserPermissionService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.LoggerFactory;

/**
 * The backend for the "root user / admin users control RBAC" admin portal. Gated by {@code
 * SecurityConfig}'s {@code /api/admin/**} -> {@code ROLE_tenant-admin} rule, so this whole
 * controller is unreachable unless the "security" profile is active AND the caller's token carries
 * the tenant-admin realm role - "who can call this" is defined in exactly one place
 * (SecurityConfig) rather than duplicated per-endpoint.
 *
 * <p><b>That is coarse-grained authentication, not authorization of the change itself</b>, and
 * treating it as sufficient was a real privilege-escalation bug: role assignment additionally needs
 * to bound WHICH roles may be granted ({@link TenantAssignableRoles}) and WHO the target may be
 * ({@link #rejectSelfRoleEdit}). Both live in this class because SecurityConfig cannot see a
 * request body. See {@link #setRoles}.
 *
 * <p><b>Fine-grained permissions are now the gate (2026-09-23).</b> What used to be assignment-only
 * storage became real enforcement: {@code SecurityConfig}'s matcher for this controller requires
 * {@code PERM_MANAGE_USERS} rather than {@code ROLE_tenant-admin}, and that authority comes from
 * the caller's stored {@code user_permission_grant} rows - so a non-admin delegated {@code
 * MANAGE_USERS} reaches this whole surface, and a tenant-admin whose grants were trimmed does not.
 * Because grants now bind everyone, {@link #requireSurvivingManageUsersHolder} exists so this UI
 * cannot strip the tenant's last enabled user-admin and lock user administration out entirely.
 *
 * <p>Every mutating call here is audited via {@link AuditLogService} - user/role/permission
 * administration is exactly the kind of action PRD Section 11 means by "every state-changing action
 * is audited."
 */
@RestController
@RequestMapping("/api/admin/users")
@Profile("security")
public class UserAdminController {

    private final IdentityUserAdminPort keycloakAdminClient;
    private final AuditLogService auditLogService;
    private final TenantLimits tenantLimits;
    private final UserPermissionService userPermissionService;
    private final AdminPermissionAuthoritySource permissionAuthoritySource;
    // Optional seam into the tenancy module (B10); null in unit tests / contexts without tenancy.
    private MemberAccountProvisioner memberAccountProvisioner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMemberAccountProvisioner(MemberAccountProvisioner memberAccountProvisioner) {
        this.memberAccountProvisioner = memberAccountProvisioner;
    }

    public UserAdminController(
            IdentityUserAdminPort keycloakAdminClient,
            AuditLogService auditLogService,
            TenantLimits tenantLimits,
            UserPermissionService userPermissionService,
            AdminPermissionAuthoritySource permissionAuthoritySource) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.auditLogService = auditLogService;
        this.tenantLimits = tenantLimits;
        this.userPermissionService = userPermissionService;
        this.permissionAuthoritySource = permissionAuthoritySource;
    }

    @GetMapping
    public List<AdminUserResponse> list() {
        return keycloakAdminClient.listUsersByTenant(tenantId()).stream()
                .map(this::withRolesAndPermissions)
                .toList();
    }

    @PostMapping
    public AdminUserResponse create(@Valid @RequestBody CreateUserRequest request) {
        // Privilege-escalation fix: "create a user with a role" is the other,
        // easily-overlooked way an arbitrary realm role from a request body
        // reaches replaceUserRealmRoles. Checked FIRST, before the user is
        // created - rejecting afterwards would leave a real Keycloak account
        // behind as the side effect of a refused request. Permissions get the
        // same check, for the same reason.
        Set<String> initialRoles = orEmpty(request.initialRoles());
        Set<String> initialPermissions = orEmpty(request.initialPermissions());
        requireAssignableRoles(initialRoles, null);
        requireAssignablePermissions(initialPermissions, null);

        // B2B/B2C/Family seat cap (hard block, not a soft warning) - checked
        // against Keycloak's own live user list rather than the AppUser
        // table, since that table is only ever populated for the original
        // signup admin (see AppUser's javadoc) and would undercount every
        // tenant that has since invited anyone.
        String tenantId = tenantId();
        int currentUserCount = keycloakAdminClient.listUsersByTenant(tenantId).size();
        int maxUsers = tenantLimits.maxUsers(tenantId);
        if (currentUserCount >= maxUsers) {
            throw new TenantLimitExceededException(
                    "The "
                            + tenantLimits.plan(tenantId)
                            + " plan allows up to "
                            + maxUsers
                            + " user"
                            + (maxUsers == 1 ? "" : "s")
                            + " - upgrade your plan to add more.");
        }
        KeycloakUserRepresentation created;
        if (request.invite()) {
            // Invite-by-email: no password is minted at all - the invitee sets
            // their own via the execute-actions link sent below, so the
            // inviting admin never sees or relays a credential.
            created =
                    keycloakAdminClient.createUserForInvite(
                            request.username(),
                            request.email(),
                            request.firstName(),
                            request.lastName(),
                            tenantId);
        } else {
            if (request.temporaryPassword() == null || request.temporaryPassword().length() < 8) {
                throw new IllegalArgumentException(
                        "Temporary password must be at least 8 characters, or send an invite email"
                                + " instead.");
            }
            created =
                    keycloakAdminClient.createUser(
                            request.username(),
                            request.email(),
                            request.firstName(),
                            request.lastName(),
                            request.temporaryPassword(),
                            tenantId);
        }
        auditLogService.record(
                actorId(),
                "admin.user.create",
                "keycloak_user",
                created.id(),
                AuditOutcome.SUCCESS,
                "Created user " + request.username() + (request.invite() ? " (invite email)" : ""));
        // B10: the member needs a control_plane.app_user row or GET /api/account 500s for them.
        // Best effort - the user already exists in Keycloak, and /api/account re-provisions a
        // missing row on first use, so a failure here must not fail the (already successful)
        // create.
        if (memberAccountProvisioner != null) {
            try {
                memberAccountProvisioner.ensureMemberAccount(
                        tenantId,
                        created.id(),
                        request.email(),
                        initialRoles.contains("tenant-admin"));
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(UserAdminController.class)
                        .warn(
                                "Could not create the app_user row for new member {}; it will be"
                                        + " created on first sign-in: {}",
                                created.id(),
                                e.toString());
            }
        }
        String inviteLink = null;
        if (request.invite()) {
            // No mail server (local auth): hand the one-time link back to the admin instead.
            inviteLink = keycloakAdminClient.issueInviteLink(created.id()).orElse(null);
        }
        if (inviteLink != null) {
            auditLogService.record(
                    actorId(),
                    "admin.user.invite_link",
                    "keycloak_user",
                    created.id(),
                    AuditOutcome.SUCCESS,
                    "Invite link issued for " + request.email());
        } else if (request.invite()) {
            // The user already exists at this point; if the email send fails
            // the account stays behind but cannot be logged into (no
            // credential) - surfaced to the admin as a normal error rather
            // than silently swallowing it, and audited as a FAILURE so the
            // orphaned-invite attempt is traceable.
            try {
                keycloakAdminClient.sendActionsEmail(
                        created.id(), List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"));
            } catch (RuntimeException e) {
                auditLogService.record(
                        actorId(),
                        "admin.user.invite_email",
                        "keycloak_user",
                        created.id(),
                        AuditOutcome.FAILURE,
                        "Invite email failed for " + request.username() + ": " + e.getMessage());
                throw e;
            }
            auditLogService.record(
                    actorId(),
                    "admin.user.invite_email",
                    "keycloak_user",
                    created.id(),
                    AuditOutcome.SUCCESS,
                    "Invite email sent to " + request.email());
        }
        if (!initialRoles.isEmpty()) {
            // Already allowlist-checked at the top of this method.
            keycloakAdminClient.replaceUserRealmRoles(created.id(), initialRoles);
            auditLogService.record(
                    actorId(),
                    "admin.user.set_roles",
                    "keycloak_user",
                    created.id(),
                    AuditOutcome.SUCCESS,
                    "Set initial roles " + initialRoles);
        }
        // Seed the new roles' default permission bundle (additive - see
        // UserPermissionService), then apply any explicit initialPermissions
        // on top. Runs even when initialRoles is empty (a no-op seed) so the
        // explicit-permissions path below always sees a consistent baseline.
        userPermissionService.seedMissingDefaultPermissions(
                tenantId, created.id(), initialRoles, actorId());
        if (!initialPermissions.isEmpty()) {
            userPermissionService.replacePermissions(
                    tenantId, created.id(), initialPermissions, actorId());
            auditLogService.record(
                    actorId(),
                    "admin.user.set_permissions",
                    "keycloak_user",
                    created.id(),
                    AuditOutcome.SUCCESS,
                    "Set initial permissions " + initialPermissions);
        }
        AdminUserResponse response = withRolesAndPermissions(created);
        return inviteLink == null ? response : response.withInviteLink(inviteLink);
    }

    /**
     * A fresh one-time invite link for someone who was invited but has not set a password yet (the
     * first link was lost or expired). Only on an install with no mail server; elsewhere the
     * invite is an e-mail and this is a 400.
     */
    @PostMapping("/{userId}/invite-link")
    public AdminUserResponse reissueInviteLink(@PathVariable String userId) {
        KeycloakUserRepresentation target = requireInTenant(userId);
        String link =
                keycloakAdminClient
                        .issueInviteLink(userId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Invites on this install are sent by e-mail;"
                                                        + " there is no link to copy."));
        auditLogService.record(
                actorId(),
                "admin.user.invite_link",
                "keycloak_user",
                userId,
                AuditOutcome.SUCCESS,
                "Invite link re-issued for " + target.email());
        return withRolesAndPermissions(target).withInviteLink(link);
    }

    /**
     * <b>Privilege-escalation fix.</b> Two guards were missing here and are both enforced now,
     * before anything reaches Keycloak:
     *
     * <ol>
     *   <li><b>Role allowlist</b> ({@link TenantAssignableRoles}) - the submitted set used to flow
     *       into {@link IdentityUserAdminPort#replaceUserRealmRoles} completely unvalidated, so a
     *       tenant-admin could grant the cross-tenant {@code platform-admin} role that gates {@code
     *       /api/platform/**}. Rejected with a 400 that names the offending roles, deliberately not
     *       filtered silently - see {@link TenantAssignableRoles}.
     *   <li><b>No self-edit</b> ({@link #rejectSelfRoleEdit}) - {@link #requireInTenant} only
     *       proves the target shares the caller's tenant, which a caller targeting their OWN user
     *       id passes trivially. Combined with self-service signup granting tenant-admin, that made
     *       this endpoint a one-request escalation path for any signed-up user. Editing your own
     *       roles is refused outright (not merely "no escalation"): there is no legitimate
     *       admin-portal flow for it, and the narrower rule would still let a compromised account
     *       silently rewrite its own role set.
     * </ol>
     */
    @PutMapping("/{userId}/roles")
    public AdminUserResponse setRoles(
            @PathVariable String userId, @Valid @RequestBody SetUserRolesRequest request) {
        rejectSelfRoleEdit(userId, request.roles());
        KeycloakUserRepresentation target = requireInTenant(userId);
        requireAssignableRoles(request.roles(), userId);
        keycloakAdminClient.replaceUserRealmRoles(userId, request.roles());
        // Additive reseed - see UserPermissionService#seedMissingDefaultPermissions.
        // A role change never claws back a permission an admin previously
        // hand-picked for this user; it only adds the new roles' defaults.
        userPermissionService.seedMissingDefaultPermissions(
                tenantId(), userId, request.roles(), actorId());
        permissionAuthoritySource.evict(tenantId(), userId);
        auditLogService.record(
                actorId(),
                "admin.user.set_roles",
                "keycloak_user",
                userId,
                AuditOutcome.SUCCESS,
                "Set roles to " + request.roles());
        return withRolesAndPermissions(target);
    }

    /**
     * The fine-grained-permissions counterpart of {@link #setRoles} - exact same guard order and
     * semantics (self-edit rejection, tenant-membership, catalog allowlist, full-set-replace,
     * audit). See this class's javadoc for why nothing downstream currently reads what's stored
     * here.
     */
    @PutMapping("/{userId}/permissions")
    public AdminUserResponse setPermissions(
            @PathVariable String userId, @Valid @RequestBody SetUserPermissionsRequest request) {
        rejectSelfPermissionEdit(userId, request.permissions());
        KeycloakUserRepresentation target = requireInTenant(userId);
        requireAssignablePermissions(request.permissions(), userId);
        if (!request.permissions().contains(Permission.MANAGE_USERS.name())
                && userPermissionService
                        .getGrantedPermissions(tenantId(), userId)
                        .contains(Permission.MANAGE_USERS.name())) {
            requireSurvivingManageUsersHolder(userId, "revoke MANAGE_USERS");
        }
        userPermissionService.replacePermissions(
                tenantId(), userId, request.permissions(), actorId());
        permissionAuthoritySource.evict(tenantId(), userId);
        auditLogService.record(
                actorId(),
                "admin.user.set_permissions",
                "keycloak_user",
                userId,
                AuditOutcome.SUCCESS,
                "Set permissions to " + request.permissions());
        return withRolesAndPermissions(target);
    }

    /**
     * Refuses any attempt to change the calling user's own realm roles. The denial is audited as a
     * FAILURE before throwing - a self-escalation attempt is exactly the event an operator reading
     * the audit log needs to see, and a silently-rejected 400 with no trail would hide it.
     */
    private void rejectSelfRoleEdit(String userId, Set<String> requestedRoles) {
        String caller = actorId();
        if (caller == null || !caller.equals(userId)) {
            return;
        }
        auditLogService.record(
                caller,
                "admin.user.set_roles",
                "keycloak_user",
                userId,
                AuditOutcome.FAILURE,
                "Refused self-edit of own roles (requested " + requestedRoles + ")");
        throw new IllegalArgumentException(
                "You cannot change your own roles. Ask another tenant-admin to make this change.");
    }

    /**
     * Same rule, same reasoning, for permissions: even though nothing currently enforces a stored
     * permission grant (see this class's javadoc), leaving self-edit open here would let a
     * tenant-admin accumulate a permission set today that additive-only reseeding (see {@link
     * UserPermissionService#seedMissingDefaultPermissions}) would never claw back after a later
     * demotion by someone else - closing that off now costs nothing and matches {@link
     * #rejectSelfRoleEdit} exactly.
     */
    private void rejectSelfPermissionEdit(String userId, Set<String> requestedPermissions) {
        String caller = actorId();
        if (caller == null || !caller.equals(userId)) {
            return;
        }
        auditLogService.record(
                caller,
                "admin.user.set_permissions",
                "keycloak_user",
                userId,
                AuditOutcome.FAILURE,
                "Refused self-edit of own permissions (requested " + requestedPermissions + ")");
        throw new IllegalArgumentException(
                "You cannot change your own permissions. Ask another tenant-admin to make this"
                        + " change.");
    }

    private static Set<String> orEmpty(Set<String> values) {
        return values == null ? Set.of() : values;
    }

    /**
     * Allowlist gate shared by {@link #setRoles} and {@link #create}'s {@code initialRoles} path.
     * Audits the denial as a FAILURE for the same reason {@link #rejectSelfRoleEdit} does, then
     * lets {@link TenantAssignableRoles}'s {@link IllegalArgumentException} (HTTP 400) propagate.
     *
     * @param targetUserId the user whose roles were being set, or {@code null} on the create path -
     *     the user does not exist yet there, precisely because this check runs first
     */
    private void requireAssignableRoles(Set<String> requestedRoles, String targetUserId) {
        try {
            TenantAssignableRoles.requireAllAssignable(requestedRoles);
        } catch (IllegalArgumentException e) {
            auditLogService.record(
                    actorId(),
                    "admin.user.set_roles",
                    "keycloak_user",
                    targetUserId,
                    AuditOutcome.FAILURE,
                    "Rejected non-assignable role request "
                            + requestedRoles
                            + ": "
                            + e.getMessage());
            throw e;
        }
    }

    /** The permission-catalog counterpart of {@link #requireAssignableRoles}. */
    private void requireAssignablePermissions(
            Set<String> requestedPermissions, String targetUserId) {
        try {
            TenantAssignablePermissions.requireAllAssignable(requestedPermissions);
        } catch (IllegalArgumentException e) {
            auditLogService.record(
                    actorId(),
                    "admin.user.set_permissions",
                    "keycloak_user",
                    targetUserId,
                    AuditOutcome.FAILURE,
                    "Rejected non-assignable permission request "
                            + requestedPermissions
                            + ": "
                            + e.getMessage());
            throw e;
        }
    }

    /**
     * Direct feedback: "provide disable option on created user" - suspends or restores a user's
     * ability to sign in, without deleting the account. Same tenant-membership guard as {@link
     * #setRoles} via {@link #requireInTenant} - without it, any tenant-admin could disable a user
     * id belonging to a completely different tenant.
     */
    @PutMapping("/{userId}/enabled")
    public AdminUserResponse setEnabled(
            @PathVariable String userId, @Valid @RequestBody SetUserEnabledRequest request) {
        KeycloakUserRepresentation target = requireInTenant(userId);
        if (!request.enabled()
                && userPermissionService
                        .getGrantedPermissions(tenantId(), userId)
                        .contains(Permission.MANAGE_USERS.name())) {
            requireSurvivingManageUsersHolder(userId, "disable");
        }
        keycloakAdminClient.setUserEnabled(userId, request.enabled());
        auditLogService.record(
                actorId(),
                request.enabled() ? "admin.user.enable" : "admin.user.disable",
                "keycloak_user",
                userId,
                AuditOutcome.SUCCESS,
                (request.enabled() ? "Enabled" : "Disabled") + " user " + target.username());
        return withRolesAndPermissions(
                new KeycloakUserRepresentation(
                        target.id(),
                        target.username(),
                        target.email(),
                        target.firstName(),
                        target.lastName(),
                        request.enabled(),
                        target.emailVerified(),
                        target.credentials(),
                        target.attributes(),
                        target.requiredActions()));
    }

    /**
     * <b>Lockout guard (granular enforcement, 2026-09-23).</b> Stored grants bind tenant-admins too
     * now, so this surface can genuinely strip the tenant's last working user-administration
     * capability: revoking {@code MANAGE_USERS} from - or disabling - the only enabled user who
     * holds it would leave NOBODY able to reach {@code /api/admin/users/**} (role alone confers
     * nothing; the permission is the gate). Refuse the change unless another enabled holder would
     * remain, and audit the refusal for the same reason {@link #rejectSelfRoleEdit} does.
     */
    private void requireSurvivingManageUsersHolder(String targetUserId, String change) {
        Set<String> holders =
                userPermissionService.userIdsHolding(tenantId(), Permission.MANAGE_USERS.name());
        for (String holderId : holders) {
            if (holderId.equals(targetUserId)) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(keycloakAdminClient.getUserById(holderId).enabled())) {
                    return;
                }
            } catch (RuntimeException e) {
                // Holder no longer exists in Keycloak (stale grant row) - it
                // can't be the surviving admin, keep looking.
            }
        }
        auditLogService.record(
                actorId(),
                "admin.user.lockout_guard",
                "keycloak_user",
                targetUserId,
                AuditOutcome.FAILURE,
                "Refused to " + change + " the last enabled MANAGE_USERS holder");
        throw new IllegalArgumentException(
                "This would remove the workspace's last enabled user-administration holder. "
                        + "Grant MANAGE_USERS to another user first.");
    }

    /**
     * Fetches a target user and confirms they actually belong to the caller's tenant before any
     * mutating call touches them - without this, {@code setRoles} would let any tenant-admin change
     * role assignments for a user id belonging to a completely different tenant (found alongside
     * {@link IdentityUserAdminPort#listUsersByTenant}, same root cause: this controller predates
     * the Stage 4 shared-realm migration and was never revisited for it). Throws the same {@link
     * AdminUserNotFoundException} whether the id doesn't exist at all or belongs to another tenant
     * - see that class's javadoc for why.
     */
    private KeycloakUserRepresentation requireInTenant(String userId) {
        KeycloakUserRepresentation user;
        try {
            user = keycloakAdminClient.getUserById(userId);
        } catch (RuntimeException e) {
            throw new AdminUserNotFoundException(userId);
        }
        boolean belongsToCaller =
                user.attributes() != null
                        && user.attributes()
                                .getOrDefault("tenant_id", List.of())
                                .contains(tenantId());
        if (!belongsToCaller) {
            throw new AdminUserNotFoundException(userId);
        }
        return user;
    }

    private AdminUserResponse withRolesAndPermissions(KeycloakUserRepresentation user) {
        List<String> roles = keycloakAdminClient.getUserRealmRoleNames(user.id());
        List<String> permissions =
                List.copyOf(userPermissionService.getGrantedPermissions(tenantId(), user.id()));
        return AdminUserResponse.from(user, roles, permissions);
    }

    private String actorId() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private String tenantId() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No TenantContext for an authenticated /api/admin/users"
                                                + " request - SecurityConfig should have blocked"
                                                + " this"));
    }
}
