package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.SignupRequest;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * B4 regression: after a failed signup the message promises "Nothing was left partially set up", yet the
 * tenant row (PROVISIONING_FAILED) and the Keycloak user stayed behind, so retrying the same organization
 * name / email answered 409 "already registered". A retry now clears the failed attempt's leftovers first,
 * and the failure path removes any user rows. Fakes only - no database or Keycloak.
 */
class TenantSignupRetryAfterFailureTest {

    private static final String ORG = "Acme Corp";
    private static final String EMAIL = "admin@acme.test";
    private static final String FAILED_SCHEMA = "acme_corp";

    private TenantRepository tenantRepository;
    private TenantProvisioningRecorder recorder;
    private KeycloakAdminClient keycloak;
    private TenantSchemaMigrator migrator;
    private TenantProvisioningService service;

    @BeforeEach
    void setUp() throws Exception {
        tenantRepository = mock(TenantRepository.class);
        recorder = mock(TenantProvisioningRecorder.class);
        keycloak = mock(KeycloakAdminClient.class);
        migrator = mock(TenantSchemaMigrator.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(mock(Connection.class, inv -> {
            if (inv.getMethod().getName().equals("createStatement")) {
                return mock(java.sql.Statement.class);
            }
            if (inv.getMethod().getName().equals("prepareStatement")) {
                // TenantSchemaGuard.schemaExists: a bound pg_namespace lookup; no schema exists yet.
                java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
                when(ps.executeQuery()).thenReturn(mock(java.sql.ResultSet.class));
                return ps;
            }
            return null;
        }));
        DisposableEmailDomainService disposable = mock(DisposableEmailDomainService.class);
        when(disposable.isDisposable(anyString())).thenReturn(false);
        when(tenantRepository.findBySchemaName(anyString())).thenReturn(Optional.empty());
        when(recorder.createProvisioningTenant(
            anyString(), any(), anyString(), anyString(), any(), any(), anyBoolean()
        )).thenAnswer(inv -> tenantRow(inv.getArgument(0), inv.getArgument(2), false));
        service = new TenantProvisioningService(tenantRepository, recorder, keycloak, dataSource, migrator, disposable, "agentic-ai");
    }

    private static Tenant tenantRow(String name, String schema, boolean failed) {
        Tenant t = new Tenant(name, TenantPlan.FREE, schema, "agentic-ai", AccountType.B2C, ExperienceMode.SIMPLE);
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        if (failed) {
            t.markProvisioningFailed();
        }
        return t;
    }

    private static SignupRequest request() {
        return new SignupRequest(ORG, EMAIL, "password123", "Ada", "Admin", null, null, null, null);
    }

    private static KeycloakUserRepresentation keycloakUser(String id, String tenantAttr) {
        return new KeycloakUserRepresentation(id, EMAIL, EMAIL, "Ada", "Admin", true, false, null,
            tenantAttr == null ? null : Map.of("tenant_id", List.of(tenantAttr)), null);
    }

    @Test
    void retryAfterFailedAttemptDiscardsTheFailedTenantAndItsKeycloakUserInsteadOf409() {
        Tenant failed = tenantRow(ORG, FAILED_SCHEMA, true);
        when(tenantRepository.findByName(ORG)).thenReturn(Optional.of(failed));
        when(tenantRepository.findBySchemaName(FAILED_SCHEMA)).thenReturn(Optional.of(failed));
        // leftover Keycloak user from the failed attempt; gone once deleted
        when(keycloak.findByEmail(EMAIL)).thenReturn(Optional.of(keycloakUser("kc-old", FAILED_SCHEMA)), Optional.empty());
        // the "already registered" check sees the row only until it is discarded
        when(tenantRepository.existsByName(ORG)).thenReturn(false);
        when(keycloak.createUserForSignup(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
            .thenReturn(keycloakUser("kc-new", null));

        assertDoesNotThrow(() -> service.signup(request()));

        verify(keycloak).deleteUser("kc-old");
        verify(recorder, org.mockito.Mockito.atLeastOnce()).discardFailedTenant(failed.getId());
    }

    @Test
    void aLiveTenantOrAnUnrelatedUserIsNeverDiscarded() {
        Tenant live = tenantRow(ORG, "acme_live", false); // ACTIVE/PROVISIONING, not failed
        when(tenantRepository.findByName(ORG)).thenReturn(Optional.of(live));
        when(tenantRepository.findBySchemaName("acme_live")).thenReturn(Optional.of(live));
        when(keycloak.findByEmail(EMAIL)).thenReturn(Optional.of(keycloakUser("kc-live", "acme_live")));
        when(tenantRepository.existsByName(ORG)).thenReturn(true);

        assertThrows(DuplicateTenantException.class, () -> service.signup(request()));

        verify(keycloak, never()).deleteUser(anyString());
        verify(recorder, never()).discardFailedTenant(any());
    }

    @Test
    void ifTheLeftoverCannotBeCleanedTheOrdinaryDuplicateCheckStillDecides() {
        Tenant failed = tenantRow(ORG, FAILED_SCHEMA, true);
        when(tenantRepository.findByName(ORG)).thenReturn(Optional.of(failed));
        when(tenantRepository.existsByName(ORG)).thenReturn(true);
        doThrow(new IllegalStateException("db down")).when(recorder).discardFailedTenant(any());

        assertThrows(DuplicateTenantException.class, () -> service.signup(request()));
    }

    @Test
    void failedProvisioningStillCompensatesAndMarksTheTenantFailed() {
        when(keycloak.createUserForSignup(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
            .thenReturn(keycloakUser("kc-new", null));
        doThrow(new IllegalStateException("flyway")).when(migrator).migrate(anyString());

        TenantProvisioningException e = assertThrows(TenantProvisioningException.class, () -> service.signup(request()));

        assertEquals(true, e.getMessage().contains("Nothing was left partially set up"));
        verify(keycloak).deleteUser("kc-new");
        verify(recorder).markFailed(any());
    }
}
