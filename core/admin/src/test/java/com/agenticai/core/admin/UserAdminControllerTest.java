package com.agenticai.core.admin;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.dto.CreateUserRequest;
import com.agenticai.core.admin.dto.SetUserPermissionsRequest;
import com.agenticai.core.admin.dto.SetUserRolesRequest;
import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.admin.permission.AdminPermissionAuthoritySource;
import com.agenticai.core.admin.permission.UserPermissionService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the {@code /api/admin/users} privilege-escalation
 * fix. The exploit these tests pin shut: self-service signup grants
 * {@code tenant-admin}, so any signed-up user could reach this controller,
 * {@code PUT /api/admin/users/{ownId}/roles {"roles":["platform-admin"]}} -
 * {@code requireInTenant} passes trivially when the target is yourself -
 * re-authenticate, and hold the cross-tenant role gating
 * {@code /api/platform/**}.
 *
 * <p>Each test asserts the Keycloak call is never made, not merely that an
 * exception was thrown: the failure mode that matters is a role reaching
 * Keycloak, and an exception raised after a successful write would still be
 * an escalation. The fine-grained-permissions endpoints added 2026-08-13
 * get the identical treatment for the identical reason.
 */
class UserAdminControllerTest {

    private static final String CALLER_SUBJECT = "caller-sub-1";
    private static final String TENANT_ID = "tenant-a";
    private static final String OTHER_USER_ID = "other-user-2";

    private KeycloakAdminClient keycloakAdminClient;
    private AuditLogService auditLogService;
    private TenantLimits tenantLimits;
    private UserPermissionService userPermissionService;
    private AdminPermissionAuthoritySource permissionAuthoritySource;
    private UserAdminController controller;

    @BeforeEach
    void setUp() {
        keycloakAdminClient = mock(KeycloakAdminClient.class);
        auditLogService = mock(AuditLogService.class);
        tenantLimits = mock(TenantLimits.class);
        userPermissionService = mock(UserPermissionService.class);
        permissionAuthoritySource = mock(AdminPermissionAuthoritySource.class);
        controller = new UserAdminController(
            keycloakAdminClient, auditLogService, tenantLimits, userPermissionService, permissionAuthoritySource);
        TenantContext.set(TenantContext.of(TENANT_ID, CALLER_SUBJECT, Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private KeycloakUserRepresentation userInCallersTenant(String id) {
        return new KeycloakUserRepresentation(
            id, "someone", "someone@example.com", "Some", "One", true, true, null,
            Map.of("tenant_id", List.of(TENANT_ID)), null);
    }

    private CreateUserRequest createRequest(Set<String> initialRoles, Set<String> initialPermissions) {
        return new CreateUserRequest(
            "newuser", "new@example.com", "New", "User", "temp-password", false, initialRoles, initialPermissions);
    }

    /** Stubs the collaborators the seat-cap check and creation call need so {@code create} can succeed. */
    private void allowCreationToSucceed(String newUserId) {
        when(keycloakAdminClient.listUsersByTenant(TENANT_ID)).thenReturn(List.of());
        when(tenantLimits.maxUsers(TENANT_ID)).thenReturn(10);
        when(keycloakAdminClient.createUser(anyString(), anyString(), anyString(), anyString(), anyString(), eq(TENANT_ID)))
            .thenReturn(userInCallersTenant(newUserId));
    }

    // --- non-allowlisted role rejected ---------------------------------------

    @Test
    void setRolesRejectsPlatformAdminAndNeverCallsKeycloak() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> controller.setRoles(
            OTHER_USER_ID, new SetUserRolesRequest(Set.of("platform-admin"))));

        assertTrue(thrown.getMessage().contains("[platform-admin]"), thrown.getMessage());
        verify(keycloakAdminClient, never()).replaceUserRealmRoles(anyString(), any());
    }

    @Test
    void setRolesRejectsAMixedSetWholesaleRatherThanApplyingTheAllowedPart() {
        // A silent filter here would apply "tenant-admin", return 200, and
        // write a success audit entry - hiding the escalation attempt.
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));

        assertThrows(IllegalArgumentException.class, () -> controller.setRoles(
            OTHER_USER_ID, new SetUserRolesRequest(Set.of("tenant-admin", "platform-admin"))));

        verify(keycloakAdminClient, never()).replaceUserRealmRoles(anyString(), any());
        verify(auditLogService, never()).record(
            any(), any(), any(), any(), eq(AuditOutcome.SUCCESS), any());
    }

    @Test
    void setRolesRejectsAnUnknownRealmRole() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));

        assertThrows(IllegalArgumentException.class, () -> controller.setRoles(
            OTHER_USER_ID, new SetUserRolesRequest(Set.of("realm-admin"))));

        verify(keycloakAdminClient, never()).replaceUserRealmRoles(anyString(), any());
    }

    @Test
    void setRolesAppliesAnAllowlistedRoleToAnotherUserInTheSameTenant() {
        // The legitimate path must still work - the four roles the admin UI
        // offers are all allowlisted, so this fix rejects nothing a real
        // console user can currently submit.
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));
        when(keycloakAdminClient.getUserRealmRoleNames(OTHER_USER_ID)).thenReturn(List.of("agent-builder"));

        controller.setRoles(OTHER_USER_ID, new SetUserRolesRequest(Set.of("agent-builder")));

        verify(keycloakAdminClient).replaceUserRealmRoles(OTHER_USER_ID, Set.of("agent-builder"));
    }

    @Test
    void setRolesReseedsDefaultPermissionsFromTheNewRoleSet() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));

        controller.setRoles(OTHER_USER_ID, new SetUserRolesRequest(Set.of("agent-builder")));

        // Additive reseed only - setRoles must never call replacePermissions
        // (a full-set-replace), which would clobber anything an admin
        // previously hand-picked for this user.
        verify(userPermissionService).seedMissingDefaultPermissions(TENANT_ID, OTHER_USER_ID, Set.of("agent-builder"), CALLER_SUBJECT);
        verify(userPermissionService, never()).replacePermissions(anyString(), anyString(), any(), anyString());
    }

    // --- self-escalation rejected --------------------------------------------

    @Test
    void aCallerCannotGrantThemselvesPlatformAdmin() {
        // The exploit, end to end: target your own user id, ask for
        // platform-admin. Must not reach Keycloak by either guard.
        assertThrows(IllegalArgumentException.class, () -> controller.setRoles(
            CALLER_SUBJECT, new SetUserRolesRequest(Set.of("tenant-admin", "platform-admin"))));

        verify(keycloakAdminClient, never()).replaceUserRealmRoles(anyString(), any());
    }

    @Test
    void aCallerCannotEditTheirOwnRolesAtAllEvenWithOnlyAllowlistedRoles() {
        // Refused outright, not merely "no escalation" - a narrower rule
        // would still let a compromised account rewrite its own role set.
        assertThrows(IllegalArgumentException.class, () -> controller.setRoles(
            CALLER_SUBJECT, new SetUserRolesRequest(Set.of("agent-builder"))));

        verify(keycloakAdminClient, never()).replaceUserRealmRoles(anyString(), any());
        // The self-edit guard runs before requireInTenant, so the target is
        // never even fetched.
        verify(keycloakAdminClient, never()).getUserById(anyString());
    }

    @Test
    void aRefusedSelfEditIsAuditedAsAFailure() {
        // A silently-rejected 400 with no trail would hide exactly the event
        // an operator needs to see.
        assertThrows(IllegalArgumentException.class, () -> controller.setRoles(
            CALLER_SUBJECT, new SetUserRolesRequest(Set.of("platform-admin"))));

        verify(auditLogService).record(
            eq(CALLER_SUBJECT), eq("admin.user.set_roles"), eq("keycloak_user"), eq(CALLER_SUBJECT),
            eq(AuditOutcome.FAILURE), anyString());
    }

    // --- the create path's initialRoles ---------------------------------------

    @Test
    void createRejectsANonAssignableInitialRoleBeforeCreatingTheUser() {
        // Checked first, so a refused request leaves no real Keycloak account
        // behind as its side effect.
        assertThrows(IllegalArgumentException.class, () -> controller.create(
            createRequest(Set.of("platform-admin"), null)));

        verify(keycloakAdminClient, never()).createUser(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(keycloakAdminClient, never()).replaceUserRealmRoles(anyString(), any());
    }

    @Test
    void createAppliesMultipleAllowlistedInitialRoles() {
        // The gap this closes: setRoles already accepted multiple roles via
        // its multi-checkbox edit UI, but create was stuck at a single
        // initialRole string. Both must now behave the same way.
        allowCreationToSucceed("new-user-1");

        controller.create(createRequest(Set.of("agent-builder", "delegated-user"), null));

        verify(keycloakAdminClient).replaceUserRealmRoles("new-user-1", Set.of("agent-builder", "delegated-user"));
    }

    @Test
    void createSeedsDefaultPermissionsFromInitialRoles() {
        allowCreationToSucceed("new-user-1");

        controller.create(createRequest(Set.of("agent-builder"), null));

        verify(userPermissionService).seedMissingDefaultPermissions(TENANT_ID, "new-user-1", Set.of("agent-builder"), CALLER_SUBJECT);
        // No explicit initialPermissions was supplied, so the full-set-replace
        // call must never run - only the additive seed.
        verify(userPermissionService, never()).replacePermissions(anyString(), anyString(), any(), anyString());
    }

    @Test
    void createAppliesExplicitInitialPermissionsOnTopOfSeededDefaults() {
        allowCreationToSucceed("new-user-1");

        controller.create(createRequest(Set.of("agent-builder"), Set.of("VIEW_AUDIT_LOG")));

        verify(userPermissionService).seedMissingDefaultPermissions(TENANT_ID, "new-user-1", Set.of("agent-builder"), CALLER_SUBJECT);
        verify(userPermissionService).replacePermissions(TENANT_ID, "new-user-1", Set.of("VIEW_AUDIT_LOG"), CALLER_SUBJECT);
    }

    @Test
    void createRejectsANonCatalogInitialPermissionBeforeCreatingTheUser() {
        assertThrows(IllegalArgumentException.class, () -> controller.create(
            createRequest(null, Set.of("SUPER_ADMIN"))));

        verify(keycloakAdminClient, never()).createUser(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // --- the setPermissions endpoint -------------------------------------------

    @Test
    void setPermissionsRejectsANonCatalogPermissionAndNeverCallsTheService() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> controller.setPermissions(
            OTHER_USER_ID, new SetUserPermissionsRequest(Set.of("SUPER_ADMIN"))));

        assertTrue(thrown.getMessage().contains("[SUPER_ADMIN]"), thrown.getMessage());
        verify(userPermissionService, never()).replacePermissions(anyString(), anyString(), any(), anyString());
    }

    @Test
    void setPermissionsRejectsAMixedSetWholesale() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));

        assertThrows(IllegalArgumentException.class, () -> controller.setPermissions(
            OTHER_USER_ID, new SetUserPermissionsRequest(Set.of("MANAGE_USERS", "SUPER_ADMIN"))));

        verify(userPermissionService, never()).replacePermissions(anyString(), anyString(), any(), anyString());
    }

    @Test
    void setPermissionsAppliesAllowlistedPermissionsToAnotherUserInTheSameTenant() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));

        controller.setPermissions(OTHER_USER_ID, new SetUserPermissionsRequest(Set.of("MANAGE_USERS", "VIEW_AUDIT_LOG")));

        verify(userPermissionService).replacePermissions(TENANT_ID, OTHER_USER_ID, Set.of("MANAGE_USERS", "VIEW_AUDIT_LOG"), CALLER_SUBJECT);
        verify(auditLogService).record(
            eq(CALLER_SUBJECT), eq("admin.user.set_permissions"), eq("keycloak_user"), eq(OTHER_USER_ID),
            eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    void aCallerCannotEditTheirOwnPermissionsAtAllEvenWithOnlyCatalogPermissions() {
        assertThrows(IllegalArgumentException.class, () -> controller.setPermissions(
            CALLER_SUBJECT, new SetUserPermissionsRequest(Set.of("MANAGE_USERS"))));

        verify(userPermissionService, never()).replacePermissions(anyString(), anyString(), any(), anyString());
        // The self-edit guard runs before requireInTenant, so the target is
        // never even fetched.
        verify(keycloakAdminClient, never()).getUserById(anyString());
    }

    @Test
    void aRefusedSelfPermissionEditIsAuditedAsAFailure() {
        assertThrows(IllegalArgumentException.class, () -> controller.setPermissions(
            CALLER_SUBJECT, new SetUserPermissionsRequest(Set.of("MANAGE_USERS"))));

        verify(auditLogService).record(
            eq(CALLER_SUBJECT), eq("admin.user.set_permissions"), eq("keycloak_user"), eq(CALLER_SUBJECT),
            eq(AuditOutcome.FAILURE), anyString());
    }

    // --- invite-by-email (granular-RBAC pass, 2026-09-23) -----------------------

    @Test
    void createWithInviteByEmailCreatesNoPasswordAndSendsTheActionsEmail() {
        when(keycloakAdminClient.listUsersByTenant(TENANT_ID)).thenReturn(List.of());
        when(tenantLimits.maxUsers(TENANT_ID)).thenReturn(10);
        when(keycloakAdminClient.createUserForInvite(anyString(), anyString(), anyString(), anyString(), eq(TENANT_ID)))
            .thenReturn(userInCallersTenant("invited-1"));

        controller.create(new CreateUserRequest(
            "invitee", "invitee@example.com", "In", "Vitee", null, true, Set.of("delegated-user"), null));

        // The temp-password path is never touched on an invite - no credential
        // exists anywhere for the admin to leak or mishandle.
        verify(keycloakAdminClient, never()).createUser(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(keycloakAdminClient).sendActionsEmail("invited-1", List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"));
        verify(auditLogService).record(
            eq(CALLER_SUBJECT), eq("admin.user.invite_email"), eq("keycloak_user"), eq("invited-1"),
            eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    void createWithInviteOnAnInstallWithoutMailReturnsTheLinkAndSendsNoEmail() {
        when(keycloakAdminClient.listUsersByTenant(TENANT_ID)).thenReturn(List.of());
        when(tenantLimits.maxUsers(TENANT_ID)).thenReturn(10);
        when(keycloakAdminClient.createUserForInvite(anyString(), anyString(), anyString(), anyString(), eq(TENANT_ID)))
            .thenReturn(userInCallersTenant("invited-2"));
        when(keycloakAdminClient.issueInviteLink("invited-2"))
            .thenReturn(java.util.Optional.of("https://console.example/login?setup=abc"));

        var response = controller.create(new CreateUserRequest(
            "invitee", "invitee@example.com", "In", "Vitee", null, true, Set.of("delegated-user"), null));

        org.junit.jupiter.api.Assertions.assertEquals("https://console.example/login?setup=abc", response.inviteLink());
        verify(keycloakAdminClient, never()).sendActionsEmail(anyString(), any());
        verify(auditLogService).record(
            eq(CALLER_SUBJECT), eq("admin.user.invite_link"), eq("keycloak_user"), eq("invited-2"),
            eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    void reissuingAnInviteLinkIsRefusedWhereInvitesAreEmailed() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));

        assertThrows(IllegalArgumentException.class, () -> controller.reissueInviteLink(OTHER_USER_ID));
    }

    @Test
    void createWithoutInviteStillRequiresATemporaryPassword() {
        when(keycloakAdminClient.listUsersByTenant(TENANT_ID)).thenReturn(List.of());
        when(tenantLimits.maxUsers(TENANT_ID)).thenReturn(10);

        assertThrows(IllegalArgumentException.class, () -> controller.create(new CreateUserRequest(
            "nopass", "nopass@example.com", "No", "Pass", null, false, null, null)));

        verify(keycloakAdminClient, never()).createUser(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(keycloakAdminClient, never()).createUserForInvite(
            anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // --- the last-MANAGE_USERS lockout guard ------------------------------------

    @Test
    void setPermissionsRefusesToRevokeTheLastEnabledManageUsersHolder() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));
        when(userPermissionService.getGrantedPermissions(TENANT_ID, OTHER_USER_ID))
            .thenReturn(Set.of("MANAGE_USERS", "VIEW_AUDIT_LOG"));
        when(userPermissionService.userIdsHolding(TENANT_ID, "MANAGE_USERS"))
            .thenReturn(Set.of(OTHER_USER_ID));

        assertThrows(IllegalArgumentException.class, () -> controller.setPermissions(
            OTHER_USER_ID, new SetUserPermissionsRequest(Set.of("VIEW_AUDIT_LOG"))));

        verify(userPermissionService, never()).replacePermissions(anyString(), anyString(), any(), anyString());
        verify(auditLogService).record(
            eq(CALLER_SUBJECT), eq("admin.user.lockout_guard"), eq("keycloak_user"), eq(OTHER_USER_ID),
            eq(AuditOutcome.FAILURE), anyString());
    }

    @Test
    void setPermissionsAllowsRevokingManageUsersWhenAnotherEnabledHolderRemains() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));
        when(keycloakAdminClient.getUserById("admin-2")).thenReturn(userInCallersTenant("admin-2"));
        when(userPermissionService.getGrantedPermissions(TENANT_ID, OTHER_USER_ID))
            .thenReturn(Set.of("MANAGE_USERS", "VIEW_AUDIT_LOG"));
        when(userPermissionService.userIdsHolding(TENANT_ID, "MANAGE_USERS"))
            .thenReturn(Set.of(OTHER_USER_ID, "admin-2"));

        controller.setPermissions(OTHER_USER_ID, new SetUserPermissionsRequest(Set.of("VIEW_AUDIT_LOG")));

        verify(userPermissionService).replacePermissions(TENANT_ID, OTHER_USER_ID, Set.of("VIEW_AUDIT_LOG"), CALLER_SUBJECT);
    }

    @Test
    void setEnabledRefusesToDisableTheLastManageUsersHolder() {
        when(keycloakAdminClient.getUserById(OTHER_USER_ID)).thenReturn(userInCallersTenant(OTHER_USER_ID));
        when(userPermissionService.getGrantedPermissions(TENANT_ID, OTHER_USER_ID))
            .thenReturn(Set.of("MANAGE_USERS"));
        when(userPermissionService.userIdsHolding(TENANT_ID, "MANAGE_USERS"))
            .thenReturn(Set.of(OTHER_USER_ID));

        assertThrows(IllegalArgumentException.class, () -> controller.setEnabled(
            OTHER_USER_ID, new com.agenticai.core.admin.dto.SetUserEnabledRequest(false)));

        verify(keycloakAdminClient, never()).setUserEnabled(anyString(), eq(false));
    }

    // --- B10: the member gets a control_plane.app_user row ---------------------

    @Test
    void createProvisionsTheAppUserRowForTheNewMember() {
        allowCreationToSucceed("new-user-1");
        MemberAccountProvisioner provisioner = mock(MemberAccountProvisioner.class);
        controller.setMemberAccountProvisioner(provisioner);

        controller.create(createRequest(Set.of("agent-builder"), Set.of()));

        verify(provisioner).ensureMemberAccount(TENANT_ID, "new-user-1", "new@example.com", false);
    }

    @Test
    void createStillSucceedsWhenTheAppUserRowCannotBeWritten() {
        // The Keycloak user already exists at that point; /api/account re-provisions lazily.
        allowCreationToSucceed("new-user-1");
        MemberAccountProvisioner provisioner = mock(MemberAccountProvisioner.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(provisioner)
            .ensureMemberAccount(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        controller.setMemberAccountProvisioner(provisioner);

        controller.create(createRequest(Set.of(), Set.of()));

        verify(provisioner).ensureMemberAccount(TENANT_ID, "new-user-1", "new@example.com", false);
    }
}
