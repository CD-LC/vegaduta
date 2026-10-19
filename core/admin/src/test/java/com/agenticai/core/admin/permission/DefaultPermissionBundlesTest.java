package com.agenticai.core.admin.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultPermissionBundlesTest {

    @Test
    void tenantAdminDefaultBundleIsEveryPermission() {
        assertEquals(TenantAssignablePermissions.ASSIGNABLE, DefaultPermissionBundles.unionForRoles(Set.of("tenant-admin")));
    }

    @Test
    void agentBuilderDefaultBundleExcludesUsersAndBilling() {
        Set<String> bundle = DefaultPermissionBundles.unionForRoles(Set.of("agent-builder"));
        assertEquals(
            Set.of("MANAGE_WORKFLOWS_ADMIN", "MANAGE_INTEGRATIONS", "MANAGE_AGENTS", "MANAGE_TOOLS"),
            bundle);
        assertTrue(!bundle.contains("MANAGE_USERS") && !bundle.contains("MANAGE_BILLING"));
    }

    @Test
    void delegatedUserAndEndCustomerDefaultToNoPermissions() {
        assertEquals(Set.of(), DefaultPermissionBundles.unionForRoles(Set.of("delegated-user")));
        assertEquals(Set.of(), DefaultPermissionBundles.unionForRoles(Set.of("end-customer")));
    }

    @Test
    void unionAcrossMultipleRolesCombinesTheirBundles() {
        Set<String> bundle = DefaultPermissionBundles.unionForRoles(Set.of("agent-builder", "delegated-user"));
        assertEquals(
            Set.of("MANAGE_WORKFLOWS_ADMIN", "MANAGE_INTEGRATIONS", "MANAGE_AGENTS", "MANAGE_TOOLS"),
            bundle);
    }

    @Test
    void unionForRolesIgnoresUnknownRoleNamesRatherThanThrowing() {
        assertEquals(Set.of(), DefaultPermissionBundles.unionForRoles(Set.of("realm-admin")));
    }

    @Test
    void nullAndEmptyRoleSetsYieldNoPermissions() {
        assertEquals(Set.of(), DefaultPermissionBundles.unionForRoles(null));
        assertEquals(Set.of(), DefaultPermissionBundles.unionForRoles(Set.of()));
    }
}
