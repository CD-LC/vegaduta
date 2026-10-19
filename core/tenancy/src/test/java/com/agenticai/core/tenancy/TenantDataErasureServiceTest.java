package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Zero test coverage existed for {@link TenantDataErasureService} before this - the class that
 * backs {@code POST /api/tenant/data-deletion}, this codebase's only irreversible
 * whole-tenant-data-destruction action. Covers, in particular, the fix landed alongside {@code
 * GraphMemoryService#eraseSubject} (roadmap §10.3 items 1-2): before that fix, {@link
 * #eraseCurrentTenant} dropped only the tenant's own Postgres schema and left its Apache AGE
 * graph-memory schema (a separately-named schema, {@code "<tenant_schema>_kg"} - see {@code
 * GraphMemorySchemaSupport}) fully intact, a real silent data-retention gap after a tenant's "erase
 * my data" request reported success.
 *
 * <p>This class keeps current-tenant erasure, the SEC-001 schema guards and demo reclaim; the
 * shared mocks are in {@link TenantDataErasureFixture}, other areas in its sibling test classes.
 */
class TenantDataErasureServiceTest extends TenantDataErasureFixture {

    @Test
    void erasureDropsBothTheTenantSchemaAndItsGraphMemorySchema() throws SQLException {
        service.eraseCurrentTenant("Acme");

        // Both DROP SCHEMA statements must run - the tenant's own schema AND its Apache AGE
        // graph-memory schema, which lives under a separately-named "<schema>_kg" schema, not
        // nested inside the tenant schema (see this class's javadoc / GraphMemorySchemaSupport).
        verify(statement).execute(contains("\"" + SCHEMA_NAME + "\""));
        verify(statement).execute(contains("\"" + SCHEMA_NAME + "_kg\""));
        verify(statement, times(2)).execute(anyString());
    }

    @Test
    void erasureDeletesEveryKeycloakUserForThisTenantBeforeDroppingSchemas() {
        KeycloakUserRepresentation userA =
                new KeycloakUserRepresentation(
                        "kc-1",
                        "a@acme.test",
                        "a@acme.test",
                        "A",
                        "One",
                        true,
                        true,
                        null,
                        null,
                        null);
        KeycloakUserRepresentation userB =
                new KeycloakUserRepresentation(
                        "kc-2",
                        "b@acme.test",
                        "b@acme.test",
                        "B",
                        "Two",
                        true,
                        true,
                        null,
                        null,
                        null);
        when(keycloakAdminClient.listUsersByTenant(SCHEMA_NAME)).thenReturn(List.of(userA, userB));

        service.eraseCurrentTenant("Acme");

        verify(keycloakAdminClient).deleteUser("kc-1");
        verify(keycloakAdminClient).deleteUser("kc-2");
    }

    @Test
    void oneFailedKeycloakUserDeleteDoesNotStopTheRestOfErasure() throws SQLException {
        KeycloakUserRepresentation userA =
                new KeycloakUserRepresentation(
                        "kc-1",
                        "a@acme.test",
                        "a@acme.test",
                        "A",
                        "One",
                        true,
                        true,
                        null,
                        null,
                        null);
        KeycloakUserRepresentation userB =
                new KeycloakUserRepresentation(
                        "kc-2",
                        "b@acme.test",
                        "b@acme.test",
                        "B",
                        "Two",
                        true,
                        true,
                        null,
                        null,
                        null);
        when(keycloakAdminClient.listUsersByTenant(SCHEMA_NAME)).thenReturn(List.of(userA, userB));
        org.mockito.Mockito.doThrow(new RuntimeException("Keycloak 500"))
                .when(keycloakAdminClient)
                .deleteUser("kc-1");

        service.eraseCurrentTenant("Acme");

        verify(keycloakAdminClient).deleteUser("kc-2");
        verify(statement, times(2)).execute(anyString());
        assertEquals(TenantStatus.ERASED, tenant.getStatus());
    }

    @Test
    void confirmOrgNameMismatchThrowsAndErasesNothing() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> service.eraseCurrentTenant("Not Acme"));

        verify(keycloakAdminClient, never()).listUsersByTenant(anyString());
        verify(statement, never()).execute(anyString());
        assertFalse(tenant.getStatus() == TenantStatus.ERASED);
    }

    @Test
    void aSchemaDropFailureIsRethrownAndTenantIsNotMarkedErased() throws SQLException {
        when(statement.execute(anyString()))
                .thenThrow(new SQLException("could not drop schema - open connections"));

        assertThrows(IllegalStateException.class, () -> service.eraseCurrentTenant("Acme"));

        // markErased/save must never run when the schema drop demonstrably failed - reporting
        // "erased" when it wasn't is the exact failure mode this method's own javadoc calls out.
        verify(tenantRepository, never()).save(any());
        assertFalse(tenant.getStatus() == TenantStatus.ERASED);
    }

    @Test
    void successfulErasureMarksTheTenantRowErasedAndSavesIt() {
        service.eraseCurrentTenant("Acme");

        assertEquals(TenantStatus.ERASED, tenant.getStatus());
        assertEquals("user-123", tenant.getErasedBy());
        assertNotNull(tenant.getErasedAt());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void noTenantContextThrowsIllegalState() {
        TenantContext.clear();

        assertThrows(IllegalStateException.class, () -> service.eraseCurrentTenant("Acme"));
    }

    // --- SEC-001: erasure never drops a protected or foreign schema ---------------------------

    @Test
    void aTenantRowPointingAtAProtectedSchemaIsNeverDropped() throws SQLException {
        for (String poisoned :
                List.of(
                        "ag_catalog",
                        "public",
                        "control_plane",
                        "pg_catalog",
                        "information_schema")) {
            Tenant bad = tenantWithSchema("Evil " + poisoned, poisoned);
            when(tenantRepository.findById(bad.getId())).thenReturn(Optional.of(bad));

            assertThrows(
                    IllegalStateException.class,
                    () -> service.eraseTenantById(bad.getId(), "Evil " + poisoned, "admin-1"));
        }
        verify(statement, never()).execute(anyString());
        verify(deletionAuditService, never()).recordInitiated(any(), any(), any());
    }

    @Test
    void aTenantRowNamedAfterAnotherTenantsGraphSchemaIsNeverDropped() throws SQLException {
        // The SEC-001 leftover: a PROVISIONING_FAILED row "victim_kg" planted by
        // the old org-name-as-schema signup. Its erasure must not drop the
        // victim's knowledge graph.
        Tenant victim = tenantWithSchema("Victim", "victim");
        Tenant planted = tenantWithSchema("victim_kg", "victim_kg");
        when(tenantRepository.findBySchemaName("victim")).thenReturn(Optional.of(victim));
        when(tenantRepository.findById(planted.getId())).thenReturn(Optional.of(planted));

        assertThrows(
                IllegalStateException.class,
                () -> service.eraseTenantById(planted.getId(), "victim_kg", "admin-1"));

        verify(statement, never()).execute(anyString());
    }

    @Test
    void theGraphDropNeverTouchesADifferentTenantsSchemaWithTheKgName() throws SQLException {
        // Legacy naming: tenant "acme" and tenant "Acme KG" (schema acme_kg)
        // coexist. Erasing "acme" must drop "acme" but NOT "acme_kg".
        org.springframework.test.util.ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        Tenant other = tenantWithSchema("Acme KG", SCHEMA_NAME + "_kg");
        when(tenantRepository.findBySchemaName(SCHEMA_NAME + "_kg")).thenReturn(Optional.of(other));

        service.eraseCurrentTenant("Acme");

        verify(statement).execute("DROP SCHEMA IF EXISTS \"" + SCHEMA_NAME + "\" CASCADE");
        verify(statement, never()).execute(contains("\"" + SCHEMA_NAME + "_kg\""));
        verify(statement, never()).execute(contains("drop_graph"));
    }

    // --- Demo-sandbox reclaim (2026-10-09): eraseDemoTenantById --------------------------------

    @Test
    void eraseDemoTenantByIdSkipsTheNameCheckForAVerifiedLegacySandbox() {
        Tenant sandbox =
                new Tenant(
                        "Demo 1a2b3c4d",
                        TenantPlan.FREE,
                        "demo_1a2b3c4d",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(sandbox));
        when(tenantRepository.isDemoSchema("demo_1a2b3c4d"))
                .thenReturn(java.util.Optional.of(true));
        when(keycloakAdminClient.listUsersByTenant("demo_1a2b3c4d")).thenReturn(List.of());

        TenantErasureResult result =
                service.eraseDemoTenantById(UUID.randomUUID(), "demo_shared", "admin-1");

        assertEquals(TenantStatus.ERASED, sandbox.getStatus());
        assertEquals("demo_1a2b3c4d", result.schemaName());
        verify(deletionAuditService)
                .recordInitiated(
                        any(),
                        org.mockito.ArgumentMatchers.eq("Demo 1a2b3c4d"),
                        org.mockito.ArgumentMatchers.eq("admin-1"));
        org.mockito.ArgumentCaptor<String> steps =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(deletionAuditService).recordCompleted(any(), steps.capture());
        assertTrue(steps.getValue().startsWith("demo reclaim: "));
    }

    @Test
    void eraseDemoTenantByIdRefusesTheSharedSandboxBeforeAnyAuditOrDeletion() throws SQLException {
        Tenant shared =
                new Tenant(
                        "Demo Sandbox",
                        TenantPlan.FREE,
                        "demo_shared",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(shared));
        when(tenantRepository.isDemoSchema("demo_shared")).thenReturn(java.util.Optional.of(true));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.eraseDemoTenantById(UUID.randomUUID(), "demo_shared", "admin-1"));

        verify(deletionAuditService, never()).recordInitiated(any(), any(), any());
        verify(statement, never()).execute(anyString());
    }

    @Test
    void eraseDemoTenantByIdRefusesARegisteredTenant() throws SQLException {
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(tenant));
        when(tenantRepository.isDemoSchema(SCHEMA_NAME)).thenReturn(java.util.Optional.of(false));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.eraseDemoTenantById(UUID.randomUUID(), "demo_shared", "admin-1"));

        verify(deletionAuditService, never()).recordInitiated(any(), any(), any());
        verify(statement, never()).execute(anyString());
        assertFalse(tenant.getStatus() == TenantStatus.ERASED);
    }
}
