package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.SignupRequest;
import com.agenticai.core.tenancy.dto.SignupResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Zero test coverage existed for {@link TenantProvisioningService} before
 * this. It is risk-bearing in two distinct ways. First,
 * {@code resolveUniqueSchemaName} is the ONE place a request-supplied org
 * name becomes a Postgres schema name AND the Keycloak {@code tenant_id}
 * attribute that every future JWT for that tenant carries - a hostile or
 * merely unlucky org name that landed on {@code public}/{@code control_plane}
 * as-is would route a brand-new tenant's queries into the pre-Stage-4
 * default schema or the platform's own cross-tenant control plane (the
 * service's RESERVED_SCHEMAS comment calls both "catastrophic", with no test
 * proving it). Second, the compensation paths: signup's rollback deletes the
 * Keycloak user it created and drops the half-made schema, while {@link
 * TenantProvisioningService#onboardExistingUser}'s rollback must NEVER
 * delete the Keycloak user - that user is the person's real federated
 * (Google-login) identity, created by the IdP flow, not by us. A regression
 * that made the two paths share one compensate would destroy real people's
 * accounts on a transient Flyway failure.
 *
 * <p>Plain JUnit 5 + Mockito, class constructed with {@code new} - the
 * {@code @Profile("security")} gate and {@code @Value} realm are irrelevant
 * to a direct-construction unit test (same posture as every controller test
 * in this module). JDBC is mocked at the DataSource-&gt;Connection-&gt;Statement
 * boundary, exactly the {@link TenantDataErasureServiceTest}/{@link
 * SchemaMultiTenantConnectionProviderTest} pattern. No live Postgres or
 * Keycloak anywhere.
 */
class TenantProvisioningServiceTest {

    private static final String REALM = "agentic-ai";
    private static final String KC_USER_ID = "kc-user-1";

    private TenantRepository tenantRepository;
    private TenantProvisioningRecorder recorder;
    private KeycloakAdminClient keycloakAdminClient;
    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private PreparedStatement namespaceQuery;
    private ResultSet namespaceResult;
    private TenantSchemaMigrator schemaMigrator;
    private DisposableEmailDomainService disposableEmailDomains;
    private TenantProvisioningService service;

    @BeforeEach
    void setUp() throws SQLException {
        tenantRepository = mock(TenantRepository.class);
        recorder = mock(TenantProvisioningRecorder.class);
        keycloakAdminClient = mock(KeycloakAdminClient.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        schemaMigrator = mock(TenantSchemaMigrator.class);
        disposableEmailDomains = mock(DisposableEmailDomainService.class);

        namespaceQuery = mock(PreparedStatement.class);
        namespaceResult = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        // SEC-001 pg_namespace pre-check: default "no such schema".
        when(connection.prepareStatement(anyString())).thenReturn(namespaceQuery);
        when(namespaceQuery.executeQuery()).thenReturn(namespaceResult);
        when(namespaceResult.next()).thenReturn(false);

        // Happy-path defaults - individual tests override the one seam they
        // are about (a schema collision, a migration failure, ...).
        when(disposableEmailDomains.isDisposable(anyString())).thenReturn(false);
        when(tenantRepository.existsByName(anyString())).thenReturn(false);
        when(tenantRepository.findBySchemaName(anyString())).thenReturn(Optional.empty());
        when(keycloakAdminClient.findByEmail(anyString())).thenReturn(Optional.empty());
        when(keycloakAdminClient.createUserForSignup(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()
        )).thenReturn(keycloakUser(KC_USER_ID));
        // onboardExistingUser reads the caller's Keycloak record to check
        // whether they are ALREADY linked to a tenant. Default: a federated
        // user with no attributes at all, i.e. not yet onboarded.
        when(keycloakAdminClient.getUserById(anyString())).thenReturn(keycloakUser(KC_USER_ID));
        // Echo whatever schema the service resolved back on the Tenant row,
        // like the real recorder would - the tests read it off the response.
        when(recorder.createProvisioningTenant(
            anyString(), any(), anyString(), anyString(), any(), any(), anyBoolean()
        )).thenAnswer(invocation -> new Tenant(
            invocation.getArgument(0, String.class), TenantPlan.FREE, invocation.getArgument(2, String.class),
            REALM, AccountType.B2C, ExperienceMode.SIMPLE
        ));

        service = new TenantProvisioningService(
            tenantRepository, recorder, keycloakAdminClient, dataSource,
            schemaMigrator, disposableEmailDomains, REALM
        );
    }

    private static KeycloakUserRepresentation keycloakUser(String id) {
        return new KeycloakUserRepresentation(
            id, "admin@acme.test", "admin@acme.test", "Ada", "Admin", true, true, null, null, null
        );
    }

    private static SignupRequest signupRequest(String orgName) {
        // accountType/experienceMode/newsOptIn/demoToken all optional-with-
        // defaults - null here exercises the same shape an older/minimal
        // caller sends.
        return new SignupRequest(orgName, "admin@acme.test", "password123", "Ada", "Admin", null, null, null, null);
    }

    // ------------------------------------------------------------------
    // SEC-001: schema names are server-generated, never the org name
    // ------------------------------------------------------------------

    private String provisionedSchema() {
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder).createProvisioningTenant(
            anyString(), any(), schemaCaptor.capture(), anyString(), any(), any(), anyBoolean()
        );
        return schemaCaptor.getValue();
    }

    @Test
    void hostileOrgNamesNeverBecomeTheSchemaName() throws SQLException {
        // Each of these used to become (a sanitised form of) the schema name:
        // another tenant's AGE graph schema, the AGE catalog, the legacy
        // default schema, the control plane, an injection attempt.
        List<String> hostile = List.of(
            "acme_corp_kg", "ag_catalog", "public", "control_plane", "pg_catalog",
            "x\"; DROP SCHEMA public CASCADE; --", "Robert'); DROP TABLE tenant;--"
        );
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        for (String orgName : hostile) {
            service.signup(signupRequest(orgName));
        }
        verify(recorder, times(hostile.size())).createProvisioningTenant(
            nameCaptor.capture(), any(), schemaCaptor.capture(), anyString(), any(), any(), anyBoolean()
        );
        for (int i = 0; i < hostile.size(); i++) {
            String schema = schemaCaptor.getAllValues().get(i);
            assertTrue(TenantSchemaGuard.isNewTenantSchemaName(schema), schema);
            assertTrue(schema.matches("^t_[a-z0-9]{16,40}$"), schema);
            assertNotEquals(SchemaNameSanitizer.sanitize(hostile.get(i)), schema);
            // The org name survives only as the display name.
            assertEquals(hostile.get(i).trim(), nameCaptor.getAllValues().get(i));
        }
        // Never a DROP on the happy path, and every CREATE targets a strict name.
        verify(statement, never()).execute(startsWith("DROP"));
        verify(statement, times(hostile.size())).execute(startsWith("CREATE SCHEMA \"t_"));
    }

    @Test
    void generatedNamesAreUniquePerSignup() {
        service.signup(signupRequest("Acme"));
        service.signup(signupRequest("Acme 2"));
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(recorder, times(2)).createProvisioningTenant(
            anyString(), any(), schemaCaptor.capture(), anyString(), any(), any(), anyBoolean()
        );
        assertNotEquals(schemaCaptor.getAllValues().get(0), schemaCaptor.getAllValues().get(1));
    }

    @Test
    void aCandidateThatAlreadyExistsInPgNamespaceIsSkipped() throws SQLException {
        // First candidate: pg_namespace says it exists -> must not be used.
        // Second candidate: free. createSchema's own re-check: free.
        when(namespaceResult.next()).thenReturn(true, false, false);

        SignupResponse response = service.signup(signupRequest("Acme"));

        ArgumentCaptor<String> probed = ArgumentCaptor.forClass(String.class);
        verify(namespaceQuery, times(3)).setString(org.mockito.ArgumentMatchers.eq(1), probed.capture());
        String rejected = probed.getAllValues().get(0);
        assertNotEquals(rejected, response.schemaName());
        assertEquals(response.schemaName(), probed.getAllValues().get(1));
        verify(connection, times(3)).prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?");
    }

    @Test
    void aCandidateAlreadyUsedByATenantRowIsSkipped() {
        when(tenantRepository.findBySchemaName(anyString()))
            .thenReturn(Optional.of(existingTenant("taken")))
            .thenReturn(Optional.empty());

        SignupResponse response = service.signup(signupRequest("Acme"));

        assertTrue(TenantSchemaGuard.isNewTenantSchemaName(response.schemaName()));
        verify(tenantRepository, times(2)).findBySchemaName(anyString());
    }

    @Test
    void theKeycloakTenantIdAttributeIsExactlyTheProvisionedSchemaAndTheOrgNameIsOnlyALabel() {
        SignupResponse response = service.signup(signupRequest("Acme"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> attributesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(keycloakAdminClient).createUserForSignup(
            anyString(), anyString(), anyString(), anyString(), anyString(), attributesCaptor.capture()
        );
        assertEquals(response.schemaName(), attributesCaptor.getValue().get("tenant_id"));
        assertEquals(provisionedSchema(), response.schemaName());
        // #958 - the human-readable org label travels alongside, unsanitized.
        assertEquals("Acme", attributesCaptor.getValue().get("organization_name"));
    }

    // ------------------------------------------------------------------
    // Compensation - SEC-001: drop ONLY what this request created
    // ------------------------------------------------------------------

    @Test
    void aFailureAfterOurCreateSchemaDropsExactlyThatSchemaDeletesTheUserMarksFailedAndRethrows() throws SQLException {
        RuntimeException migrationFailure = new RuntimeException("flyway migration broke");
        doThrow(migrationFailure).when(schemaMigrator).migrate(anyString());

        TenantProvisioningException thrown =
            assertThrows(TenantProvisioningException.class, () -> service.signup(signupRequest("acme_corp_kg")));

        assertSame(migrationFailure, thrown.getCause());
        verify(keycloakAdminClient).deleteUser(KC_USER_ID);
        String schema = provisionedSchema();
        assertTrue(TenantSchemaGuard.isNewTenantSchemaName(schema));
        verify(statement).execute("CREATE SCHEMA \"" + schema + "\"");
        verify(statement).execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        // THE SEC-001 assertion: the victim's graph schema is never touched.
        verify(statement, never()).execute(contains("acme_corp_kg"));
        verify(statement, times(1)).execute(startsWith("DROP"));
        verify(recorder).markFailed(any());
    }

    @Test
    void aFailureBeforeTheSchemaWasCreatedDropsNothing() throws SQLException {
        doThrow(new RuntimeException("keycloak role assignment failed"))
            .when(keycloakAdminClient).replaceUserRealmRoles(anyString(), any());

        assertThrows(TenantProvisioningException.class, () -> service.signup(signupRequest("ag_catalog")));

        verify(statement, never()).execute(startsWith("DROP"));
        verify(statement, never()).execute(startsWith("CREATE SCHEMA"));
        verify(keycloakAdminClient).deleteUser(KC_USER_ID);
        verify(recorder).markFailed(any());
    }

    @Test
    void aSchemaThatAppearedBetweenThePreCheckAndCreateIsNeverAdoptedOrDropped() throws SQLException {
        // allocate: free; createSchema's re-check: now exists (race / squatter).
        when(namespaceResult.next()).thenReturn(false, true);

        assertThrows(TenantProvisioningException.class, () -> service.signup(signupRequest("Acme")));

        verify(statement, never()).execute(startsWith("CREATE SCHEMA"));
        verify(statement, never()).execute(startsWith("DROP"));
        verify(schemaMigrator, never()).migrate(anyString());
        verify(recorder).markFailed(any());
    }

    @Test
    void aFailingCreateSchemaStatementIsNotTreatedAsCreatedByUs() throws SQLException {
        when(statement.execute(startsWith("CREATE SCHEMA")))
            .thenThrow(new SQLException("schema already exists", "42P06"));

        assertThrows(TenantProvisioningException.class, () -> service.signup(signupRequest("Acme")));

        verify(statement, never()).execute(startsWith("DROP"));
    }

    @Test
    void aCompensationStepThatItselfThrowsDoesNotReplaceTheOriginalExceptionOrSkipTheRest() throws SQLException {
        RuntimeException migrationFailure = new RuntimeException("flyway migration broke");
        doThrow(migrationFailure).when(schemaMigrator).migrate(anyString());
        doThrow(new RuntimeException("keycloak admin API down")).when(keycloakAdminClient).deleteUser(KC_USER_ID);

        TenantProvisioningException thrown =
            assertThrows(TenantProvisioningException.class, () -> service.signup(signupRequest("Acme")));

        assertSame(migrationFailure, thrown.getCause());
        String schema = provisionedSchema();
        verify(statement).execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        verify(recorder).markFailed(any());
    }

    @Test
    void onboardExistingUserFailureDropsOnlyItsOwnSchemaAndNeverDeletesTheFederatedKeycloakUser() throws SQLException {
        RuntimeException migrationFailure = new RuntimeException("flyway migration broke");
        doThrow(migrationFailure).when(schemaMigrator).migrate(anyString());

        TenantProvisioningException thrown = assertThrows(
            TenantProvisioningException.class,
            () -> service.onboardExistingUser("kc-federated-1", "person@gmail.com", "Ada", "Admin", "victim_kg", false)
        );

        assertSame(migrationFailure, thrown.getCause());
        String schema = provisionedSchema();
        assertTrue(TenantSchemaGuard.isNewTenantSchemaName(schema));
        verify(statement).execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        verify(statement, never()).execute(contains("victim_kg"));
        verify(recorder).markFailed(any());
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    @Test
    void onboardExistingUserFailureBeforeSchemaCreationDropsNothing() throws SQLException {
        doThrow(new RuntimeException("keycloak down"))
            .when(keycloakAdminClient).setUserAttributes(anyString(), anyMap());

        assertThrows(
            TenantProvisioningException.class,
            () -> service.onboardExistingUser("kc-federated-1", "person@gmail.com", "Ada", "Admin", "public", false)
        );

        verify(statement, never()).execute(startsWith("DROP"));
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    @Test
    void onboardingAUserWhoseKeycloakRecordAlreadyCarriesATenantProvisionsNothing() {
        when(keycloakAdminClient.getUserById("kc-federated-1")).thenReturn(new KeycloakUserRepresentation(
            "kc-federated-1", "person@gmail.com", "person@gmail.com", "Ada", "Admin", true, true, null,
            Map.of("tenant_id", List.of("acme")), null
        ));

        assertThrows(
            DuplicateTenantException.class,
            () -> service.onboardExistingUser("kc-federated-1", "person@gmail.com", "Ada", "Admin", "Acme", false)
        );

        verify(recorder, never()).createProvisioningTenant(
            anyString(), any(), anyString(), anyString(), any(), any(), anyBoolean()
        );
        verify(keycloakAdminClient, never()).setUserAttributes(anyString(), anyMap());
        verify(schemaMigrator, never()).migrate(anyString());
    }

    @Test
    void onboardingAFederatedUserWithNoTenantAttributeStillProvisionsNormally() {
        SignupResponse response =
            service.onboardExistingUser("kc-federated-1", "person@gmail.com", "Ada", "Admin", "Acme", false);

        assertTrue(TenantSchemaGuard.isNewTenantSchemaName(response.schemaName()));
        verify(keycloakAdminClient).setUserAttributes(
            "kc-federated-1", Map.of("tenant_id", response.schemaName(), "organization_name", "Acme")
        );
        verify(schemaMigrator).migrate(response.schemaName());
    }

    // ------------------------------------------------------------------
    // Abandoned-checkout rollback: legacy rows, still guarded
    // ------------------------------------------------------------------

    @Test
    void abandonedCheckoutRollbackNeverDropsProtectedOrGraphShapedSchemas() throws SQLException {
        for (String poisoned : List.of("ag_catalog", "public", "control_plane", "pg_toast", "victim_kg",
            "tenant_context_missing", "bad\"name")) {
            service.rollbackAbandonedCheckout(existingTenant(poisoned), "kc-x");
        }
        verify(statement, never()).execute(startsWith("DROP"));
        verify(recorder, times(7)).rollbackActivated(any());
    }

    @Test
    void abandonedCheckoutRollbackStillDropsALegacyTenantSchema() throws SQLException {
        service.rollbackAbandonedCheckout(existingTenant("acme"), "kc-x");
        verify(statement).execute("DROP SCHEMA IF EXISTS \"acme\" CASCADE");
        verify(keycloakAdminClient).deleteUser("kc-x");
    }

    private static Tenant existingTenant(String schemaName) {
        return new Tenant(
            "Existing " + schemaName, TenantPlan.FREE, schemaName, REALM, AccountType.B2C, ExperienceMode.SIMPLE
        );
    }
}
