package com.agenticai.core.admin.keycloak;

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
 * The allowlist is the whole fix for the {@code PUT
 * /api/admin/users/{id}/roles} privilege-escalation hole, so it gets tested
 * directly (not only through the controller): if this set ever gains
 * {@code platform-admin}, every other guard downstream is irrelevant.
 */
class TenantAssignableRolesTest {

    @Test
    void platformAdminIsNeverAssignable() {
        // The single most important assertion in this module. platform-admin
        // gates /api/platform/** - cross-tenant plan changes, the emergency
        // kill switch, the hosted-LLM provider keys.
        assertFalse(TenantAssignableRoles.isAssignable("platform-admin"));
        assertFalse(TenantAssignableRoles.ASSIGNABLE.contains(TenantAssignableRoles.PLATFORM_ADMIN));
        assertThrows(IllegalArgumentException.class,
            () -> TenantAssignableRoles.requireAllAssignable(Set.of("platform-admin")));
    }

    @Test
    void theAllowlistIsExactlyTheFourTenantScopedRealmRoles() {
        // Pinned against deploy/keycloak/agentic-ai-realm.json, which defines
        // five realm roles: these four plus platform-admin. A new realm role
        // must be added here consciously, not inherited by accident.
        assertEquals(
            Set.of("tenant-admin", "agent-builder", "delegated-user", "end-customer"),
            TenantAssignableRoles.ASSIGNABLE);
    }

    @Test
    void everyAllowlistedRoleIsAccepted() {
        assertDoesNotThrow(() -> TenantAssignableRoles.requireAllAssignable(TenantAssignableRoles.ASSIGNABLE));
    }

    @Test
    void nullAndEmptySetsAreAccepted() {
        // Clearing a user's roles is a de-escalation, never an escalation.
        assertDoesNotThrow(() -> TenantAssignableRoles.requireAllAssignable(null));
        assertDoesNotThrow(() -> TenantAssignableRoles.requireAllAssignable(Set.of()));
    }

    @Test
    void unknownRolesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> TenantAssignableRoles.requireAllAssignable(Set.of("realm-admin")));
        assertThrows(IllegalArgumentException.class,
            () -> TenantAssignableRoles.requireAllAssignable(Set.of("")));
    }

    @Test
    void matchingIsExactSoCaseAndPrefixVariantsDoNotSlipThrough() {
        assertFalse(TenantAssignableRoles.isAssignable("Tenant-Admin"));
        assertFalse(TenantAssignableRoles.isAssignable("ROLE_tenant-admin"));
        assertFalse(TenantAssignableRoles.isAssignable("tenant-admin "));
    }

    @Test
    void aNullEntryInsideTheSetIsRejectedRatherThanReachingKeycloak() {
        Set<String> withNull = new LinkedHashSet<>(Arrays.asList("tenant-admin", null));
        assertThrows(IllegalArgumentException.class, () -> TenantAssignableRoles.requireAllAssignable(withNull));
    }

    @Test
    void aMixedSetIsRejectedWholesaleAndTheMessageNamesTheOffender() {
        // Reject, never silently filter: dropping "platform-admin" and
        // applying "tenant-admin" would return 200 and write a
        // successful-looking audit entry, hiding the escalation attempt from
        // the exact log an operator would read afterwards.
        Set<String> mixed = new LinkedHashSet<>(Arrays.asList("tenant-admin", "platform-admin"));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> TenantAssignableRoles.requireAllAssignable(mixed));
        // "[platform-admin]" specifically - the rejected-names list, not the
        // trailing explanatory sentence that mentions the role either way.
        assertTrue(thrown.getMessage().contains("[platform-admin]"), thrown.getMessage());
    }

    // --- defence in depth, at the client rather than the controller ----------

    @Test
    void theKeycloakClientItselfRefusesANonAssignableRole() {
        // Enforced inside replaceUserRealmRoles too, so a future caller that
        // forgets the controller-level check still cannot mint a
        // platform-admin. It throws before obtaining an admin token, so no
        // network call is attempted and this unreachable base URL is never
        // contacted.
        KeycloakAdminClient client = new KeycloakAdminClient(
            org.springframework.web.client.RestClient.builder(),
            "http://keycloak.invalid", "agentic-ai", "agentic-ai-core", "secret");

        assertThrows(IllegalArgumentException.class,
            () -> client.replaceUserRealmRoles("some-user", Set.of("platform-admin")));
    }
}
