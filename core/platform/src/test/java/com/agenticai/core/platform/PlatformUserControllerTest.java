package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.AdminUserNotFoundException;
import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.admin.permission.AdminPermissionAuthoritySource;
import com.agenticai.core.admin.permission.UserPermissionService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlatformUserControllerTest {

    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final UserPermissionService perms = mock(UserPermissionService.class);
    private final AdminPermissionAuthoritySource authority =
            mock(AdminPermissionAuthoritySource.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AppUserRepository appUsers = mock(AppUserRepository.class);
    private final PlatformUserController controller =
            new PlatformUserController(keycloak, perms, authority, audit, tenants, appUsers);

    @BeforeEach
    void operator() {
        // The console's token: tenant-less, ROLE_platform-admin.
        TenantContext.set(TenantContext.of(null, "operator-1", Set.of("ROLE_platform-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static KeycloakUserRepresentation user(String id, String name, String tenant) {
        return new KeycloakUserRepresentation(
                id,
                name,
                name + "@x.io",
                "F",
                "L",
                true,
                true,
                List.of(),
                tenant == null ? Map.of() : Map.of("tenant_id", List.of(tenant)),
                List.of());
    }

    @Test
    void listFiltersByTenantAndQueryAndReportsTotalBeforeCap() {
        when(keycloak.listUsers())
                .thenReturn(
                        List.of(
                                user("1", "alice", "t1"),
                                user("2", "bob", "t1"),
                                user("3", "alan", "t2")));
        assertEquals(2, listBody("t1", null, 100).total());
        var byQuery = listBody(null, "AL", 100);
        assertEquals(2, byQuery.total());
        var capped = listBody(null, null, 1);
        assertEquals(3, capped.total());
        assertEquals(1, capped.users().size());
    }

    private PlatformUserController.UserListResponse listBody(String tenantId, String q, int limit) {
        return controller.list(tenantId, q, limit, null).getBody();
    }

    @Test
    void listPagesWithOffsetAndReportsTotalAndHasMoreHeaders() {
        when(keycloak.listUsers())
                .thenReturn(
                        List.of(
                                user("1", "alice", "t1"),
                                user("2", "bob", "t1"),
                                user("3", "carol", "t1")));
        var first = controller.list(null, null, 2, 0L);
        assertEquals("3", first.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", first.getHeaders().getFirst("X-Has-More"));
        assertEquals(2, first.getBody().users().size());

        var second = controller.list(null, null, 2, 2L);
        assertEquals("3", second.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", second.getHeaders().getFirst("X-Has-More"));
        assertEquals(List.of("carol"), second.getBody().users().stream().map(u -> u.username()).toList());

        var past = controller.list(null, null, 2, 99_999_999_999L);
        assertEquals("3", past.getHeaders().getFirst("X-Total-Count"));
        assertEquals(0, past.getBody().users().size());
    }

    @Test
    void refusesToTouchYourOwnAccount() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        controller.setEnabled(
                                "operator-1", new PlatformUserController.EnabledRequest(false)));
        verify(keycloak, never()).setUserEnabled(anyString(), anyBoolean());
    }

    @Test
    void refusesToTouchAnotherPlatformOperator() {
        when(keycloak.getUserById("9")).thenReturn(user("9", "ops", "t1"));
        when(keycloak.getUserRealmRoleNames("9")).thenReturn(List.of("platform-admin"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        controller.setRoles(
                                "9",
                                new PlatformUserController.RolesRequest(Set.of("tenant-admin"))));
        verify(keycloak, never()).replaceUserRealmRoles(anyString(), any());
    }

    @Test
    void refusesUserWithNoTenantAttribute() {
        when(keycloak.getUserById("5")).thenReturn(user("5", "orphan", null));
        when(keycloak.getUserRealmRoleNames("5")).thenReturn(List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.setEnabled("5", new PlatformUserController.EnabledRequest(false)));
    }

    @Test
    void cannotGrantPlatformAdminThroughTheApi() {
        when(keycloak.getUserById("4")).thenReturn(user("4", "carol", "t1"));
        when(keycloak.getUserRealmRoleNames("4")).thenReturn(List.of("member"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        controller.setRoles(
                                "4",
                                new PlatformUserController.RolesRequest(Set.of("platform-admin"))));
        verify(keycloak, never()).replaceUserRealmRoles(anyString(), any());
    }

    @Test
    void disableAppliesAndRestoresOperatorContext() {
        var u = user("4", "carol", "t1");
        when(keycloak.getUserById("4")).thenReturn(u);
        when(keycloak.getUserRealmRoleNames("4")).thenReturn(List.of("member"));
        controller.setEnabled("4", new PlatformUserController.EnabledRequest(false));
        verify(keycloak).setUserEnabled("4", false);
        assertTrue(TenantContext.current().isPresent());
        assertEquals("operator-1", TenantContext.current().get().getSubject());
    }

    @Test
    void unknownUserIs404Shaped() {
        when(keycloak.getUserById("nope")).thenThrow(new RuntimeException("404"));
        assertThrows(AdminUserNotFoundException.class, () -> controller.get("nope"));
    }

    private static Tenant tenantRow(UUID id, String schema, String name) {
        Tenant t = mock(Tenant.class);
        when(t.getId()).thenReturn(id);
        when(t.getSchemaName()).thenReturn(schema);
        when(t.getName()).thenReturn(name);
        return t;
    }

    @Test
    void tenantFilterAcceptsTheTenantUuidAsWellAsTheSchemaName() {
        UUID t1Id = UUID.randomUUID();
        Tenant t1 = tenantRow(t1Id, "t1", "Tenant One");
        when(tenants.findById(t1Id)).thenReturn(Optional.of(t1));
        when(keycloak.listUsers())
                .thenReturn(
                        List.of(
                                user("1", "alice", "t1"),
                                user("2", "bob", "t1"),
                                user("3", "alan", "t2")));

        assertEquals(2, listBody(t1Id.toString(), null, 100).total());
        assertEquals(2, listBody("t1", null, 100).total());
        // An unknown UUID matches nobody rather than everybody.
        UUID unknown = UUID.randomUUID();
        when(tenants.findById(unknown)).thenReturn(Optional.empty());
        assertEquals(0, listBody(unknown.toString(), null, 100).total());
    }

    @Test
    void rowsCarryCreatedAtLastSeenAndTenantName() {
        UUID t1Id = UUID.randomUUID();
        Tenant t1 = tenantRow(t1Id, "t1", "Tenant One");
        when(tenants.findAll()).thenReturn(List.of(t1));
        long created = Instant.parse("2026-08-01T12:00:00Z").toEpochMilli();
        KeycloakUserRepresentation alice =
                new KeycloakUserRepresentation(
                        "1",
                        "alice",
                        "a@x.io",
                        "A",
                        "L",
                        true,
                        true,
                        List.of(),
                        Map.of("tenant_id", List.of("t1")),
                        List.of(),
                        created);
        when(keycloak.listUsers()).thenReturn(List.of(alice, user("2", "zed", null)));
        AppUserRepository.KeycloakUserLastSeen seen =
                mock(AppUserRepository.KeycloakUserLastSeen.class);
        when(seen.getKeycloakUserId()).thenReturn("1");
        when(seen.getLastSeenAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
        when(appUsers.findAllLastSeen()).thenReturn(List.of(seen));

        var rows = listBody(null, null, 100).users();

        assertEquals("alice", rows.get(0).username());
        assertEquals(Instant.parse("2026-08-01T12:00:00Z"), rows.get(0).createdAt());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), rows.get(0).lastSeenAt());
        assertEquals("Tenant One", rows.get(0).tenantName());
        assertEquals("t1", rows.get(0).tenantId());
        // No tenant, no Keycloak timestamp, never seen: all null, not fabricated.
        assertEquals(null, rows.get(1).tenantName());
        assertEquals(null, rows.get(1).createdAt());
        assertEquals(null, rows.get(1).lastSeenAt());
    }

    @Test
    void aFailingDecorationLookupDoesNotFailTheList() {
        when(tenants.findAll()).thenThrow(new RuntimeException("db blip"));
        when(appUsers.findAllLastSeen()).thenThrow(new RuntimeException("db blip"));
        when(keycloak.listUsers()).thenReturn(List.of(user("1", "alice", "t1")));
        var rows = listBody(null, null, 100).users();
        assertEquals(1, rows.size());
        assertEquals(null, rows.get(0).tenantName());
    }
}
