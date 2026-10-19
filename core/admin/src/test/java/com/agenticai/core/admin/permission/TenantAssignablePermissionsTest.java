package com.agenticai.core.admin.permission;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Mirrors {@code TenantAssignableRolesTest} - the allowlist is the whole
 * bound on what {@code PUT /api/admin/users/{id}/permissions} can grant, so
 * it gets tested directly, independent of the controller.
 */
class TenantAssignablePermissionsTest {

    @Test
    void theAllowlistIsExactlyTheFifteenCatalogPermissions() {
        // Pinned against Permission.java - a new catalog entry must be added
        // here consciously, not inherited by accident.
        assertEquals(
            Set.of(
                "MANAGE_USERS", "MANAGE_BILLING", "MANAGE_INTEGRATIONS", "MANAGE_TENANT_SETTINGS",
                "MANAGE_WORKFLOWS_ADMIN", "VIEW_AUDIT_LOG", "MANAGE_APPROVALS", "MANAGE_DATA_GOVERNANCE",
                "MANAGE_CHANNELS", "MANAGE_AGENTS", "MANAGE_GUARDRAILS", "MANAGE_TOOLS",
                "MANAGE_TEMPLATES", "VIEW_USAGE", "MANAGE_LEADS"
            ),
            TenantAssignablePermissions.ASSIGNABLE);
    }

    @Test
    void everyAllowlistedPermissionIsAccepted() {
        assertDoesNotThrow(() -> TenantAssignablePermissions.requireAllAssignable(TenantAssignablePermissions.ASSIGNABLE));
    }

    @Test
    void nullAndEmptySetsAreAccepted() {
        // Clearing a user's permissions is a de-escalation, never an escalation.
        assertDoesNotThrow(() -> TenantAssignablePermissions.requireAllAssignable(null));
        assertDoesNotThrow(() -> TenantAssignablePermissions.requireAllAssignable(Set.of()));
    }

    @Test
    void unknownPermissionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> TenantAssignablePermissions.requireAllAssignable(Set.of("SUPER_ADMIN")));
        assertThrows(IllegalArgumentException.class,
            () -> TenantAssignablePermissions.requireAllAssignable(Set.of("")));
    }

    @Test
    void matchingIsExactSoCaseVariantsDoNotSlipThrough() {
        assertFalse(TenantAssignablePermissions.isAssignable("manage_users"));
        assertFalse(TenantAssignablePermissions.isAssignable("Manage_Users"));
        assertFalse(TenantAssignablePermissions.isAssignable("MANAGE_USERS "));
    }

    @Test
    void aNullEntryInsideTheSetIsRejected() {
        Set<String> withNull = new LinkedHashSet<>(Arrays.asList("MANAGE_USERS", null));
        assertThrows(IllegalArgumentException.class, () -> TenantAssignablePermissions.requireAllAssignable(withNull));
    }

    @Test
    void aMixedSetIsRejectedWholesaleAndTheMessageNamesTheOffender() {
        // Reject, never silently filter - same reasoning as
        // TenantAssignableRoles: a silent drop would return 200 and a
        // misleading audit entry.
        Set<String> mixed = new LinkedHashSet<>(Arrays.asList("MANAGE_USERS", "SUPER_ADMIN"));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> TenantAssignablePermissions.requireAllAssignable(mixed));
        assertTrue(thrown.getMessage().contains("[SUPER_ADMIN]"), thrown.getMessage());
    }
}
