package com.agenticai.core.admin.keycloak;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the rule that {@link KeycloakAdminClient#replaceUserRealmRoles} must
 * never delete Keycloak's realm default composite. On 2026-09-18 every
 * production user had lost it - signup replaces roles with exactly
 * {@code {tenant-admin}}, and the default composite is a direct mapping like
 * any other - so every app that requests {@code offline_access} (Android, iOS,
 * VS Code, Chrome, JetBrains) got "Offline tokens are not allowed for this
 * user" at sign-in.
 */
class KeycloakAdminClientDefaultRoleTest {

    @Test
    void realmDefaultCompositeIsRecognisedForAnyRealm() {
        assertTrue(KeycloakAdminClient.isRealmDefaultRole("default-roles-agentic-ai"));
        assertTrue(KeycloakAdminClient.isRealmDefaultRole("default-roles-master"));
    }

    @Test
    void managedRolesAreNotMistakenForTheDefault() {
        assertFalse(KeycloakAdminClient.isRealmDefaultRole("tenant-admin"));
        assertFalse(KeycloakAdminClient.isRealmDefaultRole("platform-admin"));
        assertFalse(KeycloakAdminClient.isRealmDefaultRole("offline_access"));
        assertFalse(KeycloakAdminClient.isRealmDefaultRole(null));
    }
}
