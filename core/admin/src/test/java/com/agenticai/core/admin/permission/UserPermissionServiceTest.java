package com.agenticai.core.admin.permission;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserPermissionServiceTest {

    private static final String TENANT_ID = "tenant-a";
    private static final String USER_ID = "user-1";
    private static final String GRANTED_BY = "admin-1";

    private UserPermissionGrantRepository repository;
    private UserPermissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserPermissionGrantRepository.class);
        // Duration.ZERO = a pass-through cache (every call runs the real
        // lookup) - keeps each case's mock stubs the only source of truth,
        // exactly the pre-cache behavior these tests were written against.
        service = new UserPermissionService(repository, java.time.Duration.ZERO);
    }

    private UserPermissionGrant grant(String permission) {
        return new UserPermissionGrant(TENANT_ID, USER_ID, permission, GRANTED_BY);
    }

    // --- replacePermissions: full-set-replace ---------------------------------

    @Test
    void replacePermissionsAddsMissingAndRemovesExtra() {
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(grant("MANAGE_BILLING")));

        service.replacePermissions(TENANT_ID, USER_ID, Set.of("MANAGE_USERS"), GRANTED_BY);

        verify(repository).deleteByTenantIdAndKeycloakUserIdAndPermissionIn(TENANT_ID, USER_ID, Set.of("MANAGE_BILLING"));
        verify(repository).save(any(UserPermissionGrant.class));
    }

    @Test
    void replacePermissionsWithAnEmptySetRevokesEverything() {
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(grant("MANAGE_BILLING"), grant("MANAGE_USERS")));

        service.replacePermissions(TENANT_ID, USER_ID, Set.of(), GRANTED_BY);

        verify(repository).deleteByTenantIdAndKeycloakUserIdAndPermissionIn(
            eq(TENANT_ID), eq(USER_ID), eq(Set.of("MANAGE_BILLING", "MANAGE_USERS")));
        verify(repository, never()).save(any());
    }

    @Test
    void replacePermissionsWithTheSameSetChangesNothing() {
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(grant("MANAGE_USERS")));

        service.replacePermissions(TENANT_ID, USER_ID, Set.of("MANAGE_USERS"), GRANTED_BY);

        verify(repository, never()).deleteByTenantIdAndKeycloakUserIdAndPermissionIn(anyString(), anyString(), any());
        verify(repository, never()).save(any());
    }

    // --- seedMissingDefaultPermissions: additive only ---------------------------

    @Test
    void seedMissingDefaultPermissionsInsertsTheRoleDefaultsWhenNoneAreGrantedYet() {
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID)).thenReturn(List.of());

        service.seedMissingDefaultPermissions(TENANT_ID, USER_ID, Set.of("agent-builder"), GRANTED_BY);

        verify(repository).saveAll(argThatInsertsExactly(
            Set.of("MANAGE_WORKFLOWS_ADMIN", "MANAGE_INTEGRATIONS", "MANAGE_AGENTS", "MANAGE_TOOLS", "SEED_MARKER")));
    }

    @Test
    void seedMissingDefaultPermissionsNeverRemovesAnExistingGrant() {
        // The critical regression this test locks in: the user already holds
        // a permission (VIEW_AUDIT_LOG) that is NOT in the new, smaller
        // role's default bundle (delegated-user grants nothing by default).
        // Reseeding after a demotion must never claw it back.
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(grant("VIEW_AUDIT_LOG")));

        service.seedMissingDefaultPermissions(TENANT_ID, USER_ID, Set.of("delegated-user"), GRANTED_BY);

        verify(repository, never()).deleteByTenantIdAndKeycloakUserIdAndPermissionIn(anyString(), anyString(), any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void seedMissingDefaultPermissionsIsIdempotent() {
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(
                grant("MANAGE_WORKFLOWS_ADMIN"), grant("MANAGE_INTEGRATIONS"),
                grant("MANAGE_AGENTS"), grant("MANAGE_TOOLS"), grant("SEED_MARKER")));

        service.seedMissingDefaultPermissions(TENANT_ID, USER_ID, Set.of("agent-builder"), GRANTED_BY);

        verify(repository, never()).saveAll(any());
    }

    @Test
    void seedMissingDefaultPermissionsOnlyInsertsWhatIsMissingNotTheWholeBundle() {
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(grant("MANAGE_WORKFLOWS_ADMIN")));

        service.seedMissingDefaultPermissions(TENANT_ID, USER_ID, Set.of("agent-builder"), GRANTED_BY);

        verify(repository).saveAll(argThatInsertsExactly(
            Set.of("MANAGE_INTEGRATIONS", "MANAGE_AGENTS", "MANAGE_TOOLS", "SEED_MARKER")));
    }

    @Test
    void seedMissingDefaultPermissionsWithNoRolesDoesNothing() {
        service.seedMissingDefaultPermissions(TENANT_ID, USER_ID, Set.of(), GRANTED_BY);

        verify(repository, never()).findByTenantIdAndKeycloakUserId(anyString(), anyString());
        verify(repository, never()).saveAll(any());
    }

    // --- the seed marker ------------------------------------------------------

    @Test
    void replacePermissionsNeverDeletesTheSeedMarkerRow() {
        // Revoking every real grant must leave the marker behind - otherwise
        // getOrSeedEffectivePermissions would read "no rows" on the next
        // request and reseed a deliberately-revoked user back to their role
        // bundle.
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(grant("MANAGE_USERS"), grant("SEED_MARKER")));

        service.replacePermissions(TENANT_ID, USER_ID, Set.of(), GRANTED_BY);

        verify(repository).deleteByTenantIdAndKeycloakUserIdAndPermissionIn(
            eq(TENANT_ID), eq(USER_ID), eq(Set.of("MANAGE_USERS")));
    }

    @Test
    void getGrantedPermissionsFiltersTheMarkerOut() {
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(grant("MANAGE_USERS"), grant("SEED_MARKER")));

        org.junit.jupiter.api.Assertions.assertEquals(
            Set.of("MANAGE_USERS"), service.getGrantedPermissions(TENANT_ID, USER_ID));
    }

    // --- getOrSeedEffectivePermissions: enforcement-time lazy seed ------------

    @Test
    void getOrSeedEffectiveReturnsStoredGrantsWithoutWriting() {
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(grant("MANAGE_BILLING"), grant("SEED_MARKER")));

        Set<String> result =
            service.getOrSeedEffectivePermissions(TENANT_ID, USER_ID, Set.of("tenant-admin"), USER_ID);

        org.junit.jupiter.api.Assertions.assertEquals(Set.of("MANAGE_BILLING"), result);
        verify(repository, never()).save(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void getOrSeedEffectiveSeedsABundledRoleUserWithNoRows() {
        // The legacy backfill: signup admins got tenant-admin straight through
        // KeycloakAdminClient and have no grant rows at all. First request
        // persists marker + full bundle so grants-bind-admins doesn't lock
        // them out on deploy.
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID)).thenReturn(List.of());

        Set<String> result =
            service.getOrSeedEffectivePermissions(TENANT_ID, USER_ID, Set.of("tenant-admin"), USER_ID);

        org.junit.jupiter.api.Assertions.assertEquals(
            TenantAssignablePermissions.ASSIGNABLE, result);
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(
            g -> "SEED_MARKER".equals(g.getPermission())));
    }

    @Test
    void getOrSeedEffectiveMarkerOnlyRowsMeanRevokedAllNotReseed() {
        // Marker survives revoke-to-empty (test above); a later read must
        // return empty WITHOUT reseeding - the admin's revocation stands.
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID))
            .thenReturn(List.of(grant("SEED_MARKER")));

        Set<String> result =
            service.getOrSeedEffectivePermissions(TENANT_ID, USER_ID, Set.of("tenant-admin"), USER_ID);

        org.junit.jupiter.api.Assertions.assertEquals(Set.of(), result);
        verify(repository, never()).save(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void getOrSeedEffectiveUnbundledRoleWithNoRowsWritesNothing() {
        // delegated-user has an empty bundle: nothing to seed, and no marker
        // either (there is no "revoked vs never-seeded" ambiguity to resolve
        // when the bundle itself is empty).
        when(repository.findByTenantIdAndKeycloakUserId(TENANT_ID, USER_ID)).thenReturn(List.of());

        Set<String> result =
            service.getOrSeedEffectivePermissions(TENANT_ID, USER_ID, Set.of("delegated-user"), USER_ID);

        org.junit.jupiter.api.Assertions.assertEquals(Set.of(), result);
        verify(repository, never()).save(any());
        verify(repository, never()).saveAll(any());
    }

    // --- userIdsHolding: lockout-guard read -------------------------------------

    @Test
    void userIdsHoldingReturnsTheDistinctHoldersOfOnePermission() {
        when(repository.findByTenantIdAndPermission(TENANT_ID, "MANAGE_USERS"))
            .thenReturn(List.of(grant("MANAGE_USERS"), grant("MANAGE_USERS")));

        Set<String> result = service.userIdsHolding(TENANT_ID, "MANAGE_USERS");

        org.junit.jupiter.api.Assertions.assertEquals(Set.of(USER_ID), result);
    }

    private static List<UserPermissionGrant> argThatInsertsExactly(Set<String> expectedPermissions) {
        return argThat(inserted -> {
            if (inserted == null) {
                return expectedPermissions.isEmpty();
            }
            Set<String> actual = new HashSet<>();
            for (UserPermissionGrant g : inserted) {
                actual.add(g.getPermission());
            }
            return actual.equals(expectedPermissions);
        });
    }
}
