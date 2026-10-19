package com.agenticai.core.platform.impersonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.platform.ops.PlatformTenantAuditor;
import com.agenticai.core.security.ImpersonationGrantResolver.ViewAsSession;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ImpersonationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String SCHEMA = "tenant_acme";
    private static final String ADMIN = "admin-kc";
    private static final String TARGET = "target-kc";
    private static final String REASON = "Customer ticket #4411: agents page shows nothing";

    private JdbcTemplate jdbc;
    private TenantRepository tenants;
    private AppUserRepository appUsers;
    private KeycloakAdminClient keycloak;
    private PlatformTenantAuditor auditor;
    private Tenant tenant;
    private ImpersonationService service;
    private final List<Object[]> inserts = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tenants = mock(TenantRepository.class);
        appUsers = mock(AppUserRepository.class);
        keycloak = mock(KeycloakAdminClient.class);
        auditor = mock(PlatformTenantAuditor.class);
        tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT_ID);
        when(tenant.getName()).thenReturn("Acme");
        when(tenant.getSchemaName()).thenReturn(SCHEMA);
        when(tenant.getStatus()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT_ID)).thenReturn(Optional.of(tenant));
        when(tenants.isDemoSchema(SCHEMA)).thenReturn(Optional.of(false));
        when(appUsers.findByKeycloakUserId(anyString())).thenReturn(Optional.empty());
        when(keycloak.getUserById(TARGET)).thenReturn(user(TARGET, SCHEMA));
        when(keycloak.getUserRealmRoleNames(TARGET)).thenReturn(List.of("tenant-admin", "user"));
        when(jdbc.queryForObject(eq(ImpersonationService.SQL_COUNT_ACTIVE_FOR_ADMIN), eq(Long.class), any(Object[].class)))
            .thenReturn(0L);
        when(jdbc.update(eq(ImpersonationService.SQL_INSERT), any(Object[].class))).thenAnswer(inv -> {
            inserts.add(Arrays.copyOfRange(inv.getArguments(), 1, inv.getArguments().length));
            return 1;
        });
        service = service("https://app.vegaduta.ai");
    }

    private ImpersonationService service(String publicUrl) {
        return new ImpersonationService(jdbc, new TransactionTemplate(mock(PlatformTransactionManager.class)),
            tenants, appUsers, keycloak, auditor, publicUrl, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static KeycloakUserRepresentation user(String id, String tenantAttr) {
        return new KeycloakUserRepresentation(id, "u", "user@acme.co", null, null, true, true, null,
            tenantAttr == null ? Map.of() : Map.of("tenant_id", List.of(tenantAttr)), null);
    }

    // ------------------------------------------------------------------ start

    @Test
    void startReturnsGrantOnceAndPersistsOnlyItsHash() {
        ImpersonationService.StartedSession s = service.start(ADMIN, "ops@vegaduta.ai", TENANT_ID, TARGET, REASON);

        assertNotNull(s.grant());
        assertTrue(s.grant().length() >= 43, "32 random bytes, base64url");
        assertEquals(NOW, s.startedAt());
        assertEquals(NOW.plusSeconds(1800), s.expiresAt());
        assertEquals("Acme", s.tenantName());
        assertEquals("user@acme.co", s.userEmail());
        assertEquals("https://app.vegaduta.ai/view-as#grant=" + s.grant(), s.viewUrl());

        assertEquals(1, inserts.size());
        Object[] row = inserts.get(0);
        assertTrue(Arrays.asList(row).contains(ImpersonationService.sha256Hex(s.grant())));
        for (Object value : row) {
            assertFalse(String.valueOf(value).contains(s.grant()), "raw grant must never be persisted");
        }
        assertEquals("tenant-admin,user", row[7]);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditor).record(eq(SCHEMA), eq(ADMIN), eq("platform.impersonation.start"), eq("user"), eq(TARGET),
            detail.capture());
        assertTrue(detail.getValue().contains("ops@vegaduta.ai"));
        assertTrue(detail.getValue().contains(REASON));
        assertFalse(detail.getValue().contains(s.grant()));
        verify(jdbc).query(eq(ImpersonationService.SQL_LOCK_ADMIN), any(ResultSetExtractor.class), eq(ADMIN));
    }

    @Test
    void reasonMustBeAtLeastTenCharacters() {
        assertThrows(IllegalArgumentException.class, () -> service.start(ADMIN, null, TENANT_ID, TARGET, " too short "));
        assertThrows(IllegalArgumentException.class, () -> service.start(ADMIN, null, TENANT_ID, TARGET, null));
        assertTrue(inserts.isEmpty());
    }

    @Test
    void erasedDemoAndInactiveTenantsAreRefused() {
        when(tenant.getStatus()).thenReturn(TenantStatus.ERASED);
        assertEquals(409, status(() -> service.start(ADMIN, null, TENANT_ID, TARGET, REASON)));
        when(tenant.getStatus()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.isDemoSchema(SCHEMA)).thenReturn(Optional.of(true));
        assertEquals(409, status(() -> service.start(ADMIN, null, TENANT_ID, TARGET, REASON)));
        when(tenants.isDemoSchema(SCHEMA)).thenReturn(Optional.of(false));
        when(tenant.getStatus()).thenReturn(TenantStatus.SUSPENDED);
        assertEquals(409, status(() -> service.start(ADMIN, null, TENANT_ID, TARGET, REASON)));
        assertTrue(inserts.isEmpty());
    }

    @Test
    void targetFromAnotherTenantIsRefused() {
        when(keycloak.getUserById(TARGET)).thenReturn(user(TARGET, "tenant_other"));
        assertEquals(404, status(() -> service.start(ADMIN, null, TENANT_ID, TARGET, REASON)));
        assertTrue(inserts.isEmpty());
    }

    @Test
    void targetWithoutAttributeOrAppUserIsRefused() {
        when(keycloak.getUserById(TARGET)).thenReturn(user(TARGET, null));
        assertEquals(404, status(() -> service.start(ADMIN, null, TENANT_ID, TARGET, REASON)));
    }

    @Test
    void platformAdminTargetIsRefused() {
        when(keycloak.getUserRealmRoleNames(TARGET)).thenReturn(List.of("platform-admin"));
        assertEquals(403, status(() -> service.start(ADMIN, null, TENANT_ID, TARGET, REASON)));
        assertTrue(inserts.isEmpty());
    }

    @Test
    void viewingAsYourselfIsRefused() {
        assertEquals(400, status(() -> service.start(TARGET, null, TENANT_ID, TARGET, REASON)));
    }

    @Test
    void atMostThreeActiveSessionsPerAdmin() {
        when(jdbc.queryForObject(eq(ImpersonationService.SQL_COUNT_ACTIVE_FOR_ADMIN), eq(Long.class), any(Object[].class)))
            .thenReturn(3L);
        assertEquals(409, status(() -> service.start(ADMIN, null, TENANT_ID, TARGET, REASON)));
        assertTrue(inserts.isEmpty());
        verify(auditor, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void viewUrlIsNullWhenPublicUrlIsUnsetOrLocal() {
        assertNull(service("").viewUrl("g"));
        assertNull(service(null).viewUrl("g"));
        assertNull(service("http://localhost:3000").viewUrl("g"));
        assertNull(service("http://127.0.0.1:3000").viewUrl("g"));
        assertNull(service("http://app.localhost").viewUrl("g"));
        assertEquals("https://app.vegaduta.xyz/view-as#grant=g", service("https://app.vegaduta.xyz/").viewUrl("g"));
    }

    // ------------------------------------------------------------------ resolve / end

    @SuppressWarnings("unchecked")
    private void stubLookup(ImpersonationService.Row row) {
        when(jdbc.query(eq(ImpersonationService.SQL_BY_HASH), any(RowMapper.class), anyString()))
            .thenReturn(row == null ? List.of() : List.of(row));
        when(jdbc.query(eq(ImpersonationService.SQL_BY_ID), any(RowMapper.class), any(UUID.class)))
            .thenReturn(row == null ? List.of() : List.of(row));
    }

    private static ImpersonationService.Row row(UUID id, String grant, Instant endedAt) {
        return new ImpersonationService.Row(id, ADMIN, "ops@vegaduta.ai", TENANT_ID, SCHEMA, "Acme", TARGET,
            "user@acme.co", List.of("user"), REASON, ImpersonationService.sha256Hex(grant), NOW, NOW.plusSeconds(1800),
            endedAt, 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveLooksUpByHashAndCaches() {
        UUID id = UUID.randomUUID();
        stubLookup(row(id, "the-grant", null));

        ViewAsSession s = service.resolve("the-grant").orElseThrow();
        service.resolve("the-grant");

        assertEquals(id, s.id());
        assertEquals(SCHEMA, s.tenantSchema());
        assertEquals(ADMIN, s.adminKeycloakId());
        verify(jdbc, times(1)).query(eq(ImpersonationService.SQL_BY_HASH), any(RowMapper.class),
            eq(ImpersonationService.sha256Hex("the-grant")));
        assertTrue(service.resolve("another").isEmpty(), "a row whose hash differs is never returned");
    }

    @Test
    void unknownGrantResolvesEmpty() {
        stubLookup(null);
        assertTrue(service.resolve("nope").isEmpty());
        assertTrue(service.resolve("  ").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void endingEvictsTheCacheAndAuditsOnce() {
        UUID id = UUID.randomUUID();
        stubLookup(row(id, "g1", null));
        service.resolve("g1");
        when(jdbc.update(eq(ImpersonationService.SQL_END), any(Object[].class))).thenReturn(1, 0);

        service.end(id, ADMIN, "ops@vegaduta.ai");
        service.resolve("g1");
        verify(jdbc, times(2)).query(eq(ImpersonationService.SQL_BY_HASH), any(RowMapper.class), anyString());
        verify(auditor).record(eq(SCHEMA), eq(ADMIN), eq("platform.impersonation.end"), eq("user"), eq(TARGET),
            contains(REASON));

        service.end(id, ADMIN, "ops@vegaduta.ai"); // idempotent
        verify(auditor, times(1)).record(any(), any(), eq("platform.impersonation.end"), any(), any(), any());
    }

    @Test
    void endingAnUnknownSessionIs404() {
        stubLookup(null);
        assertEquals(404, status(() -> service.end(UUID.randomUUID(), ADMIN, null)));
    }

    @Test
    void requestCountsAreBatchedNotWrittenPerRequest() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            service.recordRequest(id);
        }
        verify(jdbc, never()).update(eq(ImpersonationService.SQL_FLUSH_REQUESTS), any(Object[].class));
        service.flushRequestCounts();
        verify(jdbc, times(1)).update(ImpersonationService.SQL_FLUSH_REQUESTS, 50L, Timestamp.from(NOW),
            Timestamp.from(NOW), id);
        service.flushRequestCounts();
        verify(jdbc, times(1)).update(eq(ImpersonationService.SQL_FLUSH_REQUESTS), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listIsOneBasedWithDefaultLimit() {
        when(jdbc.query(eq(ImpersonationService.SQL_LIST_ACTIVE), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of(row(UUID.randomUUID(), "g", null)));
        when(jdbc.queryForObject(eq(ImpersonationService.SQL_COUNT_ACTIVE), eq(Long.class), any(Object[].class)))
            .thenReturn(1L);
        ImpersonationService.SessionPage page = service.list(true, null, null);
        assertEquals(1, page.total());
        assertEquals("user@acme.co", page.items().get(0).userEmail());
        assertEquals("ops@vegaduta.ai", page.items().get(0).adminEmail());
        verify(jdbc).query(eq(ImpersonationService.SQL_LIST_ACTIVE), any(RowMapper.class),
            eq(Timestamp.from(NOW)), eq(25), eq(0));
        service.list(true, 3, 10);
        verify(jdbc).query(eq(ImpersonationService.SQL_LIST_ACTIVE), any(RowMapper.class),
            eq(Timestamp.from(NOW)), eq(10), eq(20));
    }

    private static int status(Runnable r) {
        try {
            r.run();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        } catch (com.agenticai.core.platform.PlatformTenantNotFoundException e) {
            return 404;
        }
        return 200;
    }
}
