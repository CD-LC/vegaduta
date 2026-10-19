package com.agenticai.core.admin.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.TenantLimits;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The auth boundary is the load-bearing part: a {@code vdscim_*} token must
 * scope every operation to ITS tenant's users and nothing else. These tests
 * pin unauthorized/short-circuit behavior and the tenant_id cross-check on
 * user-by-id paths.
 */
class ScimProvisioningControllerTest {

    private TenantScimTokenRepository tokenRepository;
    private KeycloakAdminClient keycloak;
    private TenantLimits tenantLimits;
    private ScimProvisioningController controller;

    private static final String TOKEN = "vdscim_abc123";
    private static final String TENANT = "tenant_acme";

    @BeforeEach
    void setUp() {
        tokenRepository = mock(TenantScimTokenRepository.class);
        keycloak = mock(KeycloakAdminClient.class);
        tenantLimits = mock(TenantLimits.class);
        // Default: plenty of seats, so pre-SEC-053 tests keep asserting what they did.
        when(tenantLimits.maxUsers(TENANT)).thenReturn(Integer.MAX_VALUE);
        // Plan gate's default posture in these tests: the tenant HAS the
        // scim entitlement; the gate-specific tests below flip it off.
        when(tenantLimits.scimAllowed(TENANT)).thenReturn(true);
        controller = new ScimProvisioningController(tokenRepository, keycloak, tenantLimits);

        TenantScimToken row = new TenantScimToken();
        row.setTenantSchema(TENANT);
        row.setTokenHash(ScimProvisioningController.sha256Hex(TOKEN));
        when(tokenRepository.findByTokenHashAndRevokedAtIsNull(ScimProvisioningController.sha256Hex(TOKEN)))
            .thenReturn(Optional.of(row));
    }

    private static KeycloakUserRepresentation user(String id, String tenantId) {
        return new KeycloakUserRepresentation(
            id, "jane@acme.com", "jane@acme.com", "Jane", "Doe",
            true, false, null,
            Map.of("tenant_id", List.of(tenantId)), null);
    }

    @Test
    void missingBearer_is401_notAServerError() {
        ResponseEntity<?> res = controller.listUsers(null);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        res = controller.listUsers("Basic dXNlcjpwYXNz");
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        res = controller.listUsers("Bearer wrong-token");
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        verify(keycloak, never()).listUsersByTenant(any());
    }

    @Test
    void listUsers_scopesToTokensTenant() {
        when(keycloak.listUsersByTenant(TENANT)).thenReturn(List.of(user("u1", TENANT)));
        ResponseEntity<?> res = controller.listUsers("Bearer " + TOKEN);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(keycloak).listUsersByTenant(TENANT);
        verify(keycloak, never()).listUsersByTenant("tenant_other");
    }

    @Test
    void createUser_passesTokenTenant_toKeycloak() {
        when(keycloak.findByEmail("jane@acme.com")).thenReturn(Optional.empty());
        KeycloakUserRepresentation created = user("u9", TENANT);
        when(keycloak.createUserForInvite(eq("jane@acme.com"), eq("jane@acme.com"), any(), any(), eq(TENANT)))
            .thenReturn(created);
        when(keycloak.getUserById("u9")).thenReturn(created);

        ResponseEntity<?> res = controller.createUser(
            "Bearer " + TOKEN,
            Map.of("userName", "jane@acme.com",
                   "name", Map.of("givenName", "Jane", "familyName", "Doe")));
        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        verify(keycloak).createUserForInvite("jane@acme.com", "jane@acme.com", "Jane", "Doe", TENANT);
    }

    @Test
    void getUser_otherTenantsUser_is404_notLeak() {
        when(keycloak.getUserById("u-foreign")).thenReturn(user("u-foreign", "tenant_other"));
        ResponseEntity<?> res = controller.getUser("Bearer " + TOKEN, "u-foreign");
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
    }

    @Test
    void patchUser_onlyActiveFlag_isHonored() {
        when(keycloak.getUserById("u1")).thenReturn(user("u1", TENANT));
        // SCIM PATCH operations shape
        ResponseEntity<?> res = controller.patchUser("Bearer " + TOKEN, "u1", Map.of(
            "Operations", List.of(Map.of("op", "replace", "path", "active", "value", false))));
        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(keycloak).setUserEnabled("u1", false);

        // A PATCH carrying no active replacement is a SCIM 400, not silent success
        res = controller.patchUser("Bearer " + TOKEN, "u1",
            Map.of("Operations", List.of(Map.of("op", "replace", "path", "displayName", "value", "X"))));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    }

    @Test
    void createUser_withoutScimEntitlement_is403() {
        // Downgraded tenant: an already-minted token can still read and
        // deprovision, but must not be able to provision new seats.
        when(tenantLimits.scimAllowed(TENANT)).thenReturn(false);
        ResponseEntity<?> res = controller.createUser(
            "Bearer " + TOKEN, Map.of("userName", "jane@acme.com"));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verify(keycloak, never()).createUserForInvite(any(), any(), any(), any(), any());
    }

    @Test
    void patchReactivate_withoutScimEntitlement_is403_butDeprovisionStillWorks() {
        when(keycloak.getUserById("u1")).thenReturn(user("u1", TENANT));
        when(tenantLimits.scimAllowed(TENANT)).thenReturn(false);
        // active:true is provisioning-shaped - blocked.
        ResponseEntity<?> res = controller.patchUser("Bearer " + TOKEN, "u1", Map.of("active", true));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verify(keycloak, never()).setUserEnabled("u1", true);
        // active:false is cleanup - must keep working after the entitlement ends.
        res = controller.patchUser("Bearer " + TOKEN, "u1", Map.of("active", false));
        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(keycloak).setUserEnabled("u1", false);
    }

    @Test
    void deleteUser_disablesRatherThanDeletes() {
        when(keycloak.getUserById("u1")).thenReturn(user("u1", TENANT));
        ResponseEntity<?> res = controller.deleteUser("Bearer " + TOKEN, "u1");
        assertEquals(HttpStatus.NO_CONTENT, res.getStatusCode());
        verify(keycloak).setUserEnabled("u1", false);
    }

    @Test
    void keycloakMiss_onGetUserById_mapsTo404() {
        when(keycloak.getUserById("gone")).thenThrow(new RuntimeException("404 from keycloak"));
        ResponseEntity<?> res = controller.getUser("Bearer " + TOKEN, "gone");
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertNotNull(res.getBody());
    }

    // --- SEC-053: SCIM honours the plan user limit ---------------------------------------

    @Test
    void createUser_atThePlanUserLimit_isRejectedAndNothingIsCreated() {
        when(keycloak.findByEmail("new@acme.com")).thenReturn(Optional.empty());
        when(keycloak.listUsersByTenant(TENANT)).thenReturn(List.of(user("u1", TENANT), user("u2", TENANT)));
        when(tenantLimits.maxUsers(TENANT)).thenReturn(2);

        ResponseEntity<?> res = controller.createUser("Bearer " + TOKEN, Map.of("userName", "new@acme.com"));

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertNotNull(body);
        assertEquals(List.of("urn:ietf:params:scim:api:messages:2.0:Error"), body.get("schemas"));
        verify(keycloak, never()).createUserForInvite(any(), any(), any(), any(), any());
    }

    @Test
    void createUser_belowThePlanUserLimit_isCreated() {
        when(keycloak.findByEmail("new@acme.com")).thenReturn(Optional.empty());
        when(keycloak.listUsersByTenant(TENANT)).thenReturn(List.of(user("u1", TENANT)));
        when(tenantLimits.maxUsers(TENANT)).thenReturn(2);
        KeycloakUserRepresentation created = user("u9", TENANT);
        when(keycloak.createUserForInvite(eq("new@acme.com"), eq("new@acme.com"), any(), any(), eq(TENANT)))
            .thenReturn(created);
        when(keycloak.getUserById("u9")).thenReturn(created);

        ResponseEntity<?> res = controller.createUser("Bearer " + TOKEN, Map.of("userName", "new@acme.com"));

        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        verify(tenantLimits).maxUsers(TENANT);
    }

    @Test
    void createUser_limitIsCheckedAgainstTheTokensTenantOnly() {
        when(keycloak.findByEmail("new@acme.com")).thenReturn(Optional.empty());
        when(keycloak.listUsersByTenant(TENANT)).thenReturn(List.of(user("u1", TENANT)));
        when(tenantLimits.maxUsers(TENANT)).thenReturn(1);

        ResponseEntity<?> res = controller.createUser("Bearer " + TOKEN, Map.of("userName", "new@acme.com"));

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verify(keycloak, never()).listUsersByTenant("tenant_other");
    }
}
