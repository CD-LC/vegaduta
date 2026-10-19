package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.AdminUserNotFoundException;
import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PlatformSessionControllerTest {

    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PlatformTenantAuditor auditor = mock(PlatformTenantAuditor.class);
    private final PlatformSessionController controller = new PlatformSessionController(keycloak, tenants, auditor);

    @BeforeEach
    void operator() {
        TenantContext.set(TenantContext.of(null, "ops-1", Set.of("ROLE_platform-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static KeycloakUserRepresentation user(String id, String tenant) {
        KeycloakUserRepresentation u = mock(KeycloakUserRepresentation.class);
        when(u.id()).thenReturn(id);
        when(u.username()).thenReturn("user-" + id);
        when(u.attributes()).thenReturn(tenant == null ? null : Map.of("tenant_id", List.of(tenant)));
        return u;
    }

    @Test
    void logsOutAUserAndAuditsInTheirTenant() {
        KeycloakUserRepresentation u = user("kc-1", "acme");
        when(keycloak.getUserById("kc-1")).thenReturn(u);
        when(keycloak.getUserRealmRoleNames("kc-1")).thenReturn(List.of("tenant-admin"));

        assertEquals(Map.of("ok", true), controller.logoutUser("kc-1"));

        verify(keycloak).logoutUser("kc-1");
        verify(auditor).record(eq("acme"), eq("ops-1"), eq("platform.user.logout"), eq("keycloak_user"), eq("kc-1"),
            anyString());
    }

    @Test
    void refusesPlatformAdminsAndSelfWith403() {
        KeycloakUserRepresentation admin = user("kc-2", null);
        when(keycloak.getUserById("kc-2")).thenReturn(admin);
        when(keycloak.getUserRealmRoleNames("kc-2")).thenReturn(List.of("platform-admin"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> controller.logoutUser("kc-2"));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());

        KeycloakUserRepresentation self = user("ops-1", null);
        when(keycloak.getUserById("ops-1")).thenReturn(self);
        assertThrows(ResponseStatusException.class, () -> controller.logoutUser("ops-1"));
        verify(keycloak, never()).logoutUser(anyString());
    }

    @Test
    void unknownUserIs404() {
        when(keycloak.getUserById("nope")).thenThrow(new RuntimeException("404"));
        assertThrows(AdminUserNotFoundException.class, () -> controller.logoutUser("nope"));
    }

    @Test
    void logoutAllCountsOnlyUsersActuallyLoggedOut() {
        UUID id = UUID.randomUUID();
        Tenant t = mock(Tenant.class);
        when(t.getSchemaName()).thenReturn("acme");
        when(tenants.findById(id)).thenReturn(Optional.of(t));
        List<KeycloakUserRepresentation> members = List.of(
            user("a", "acme"), user("b", "acme"), user("admin", "acme"), user("ops-1", "acme"), user("broken", "acme"));
        when(keycloak.listUsersByTenant("acme")).thenReturn(members);
        when(keycloak.getUserRealmRoleNames(anyString())).thenReturn(List.of("tenant-member"));
        when(keycloak.getUserRealmRoleNames("admin")).thenReturn(List.of("platform-admin"));
        doThrow(new RuntimeException("kc down")).when(keycloak).logoutUser("broken");

        assertEquals(Map.of("usersLoggedOut", 2), controller.logoutTenant(id.toString()));

        verify(keycloak).logoutUser("a");
        verify(keycloak).logoutUser("b");
        verify(keycloak, never()).logoutUser("admin");
        verify(keycloak, never()).logoutUser("ops-1");
        verify(auditor).record(eq("acme"), eq("ops-1"), eq("platform.tenant.logout_all"), eq("tenant"), eq("acme"),
            contains("2 user(s); 1 failed"));
    }

    @Test
    void logoutAllUnknownTenantIs404() {
        when(tenants.findBySchemaName("nope")).thenReturn(Optional.empty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> controller.logoutTenant("nope"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
