package com.agenticai.core.tenancy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.storage.FileStorageService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared mocks and the {@code "acme"} tenant for the {@link TenantDataErasureService} tests,
 * which are split by behaviour area: {@link TenantDataErasureServiceTest} (current-tenant erasure,
 * SEC-001 schema guards, demo reclaim), {@link TenantErasureObjectStorageTest}, {@link
 * TenantEraseByIdTest} and {@link TenantErasureCompletenessTest}.
 *
 * <p>Uses the real (not mocked) {@link TenantContext} static holder - it's a plain ThreadLocal,
 * mocking it would test nothing real - with {@link TenantContext#clear()} in {@code @AfterEach}
 * so no state leaks between tests.
 */
abstract class TenantDataErasureFixture {

    protected static final String SCHEMA_NAME = "acme";

    protected TenantRepository tenantRepository;
    protected KeycloakAdminClient keycloakAdminClient;
    protected DataSource dataSource;
    protected Connection connection;
    protected Statement statement;
    protected ResultSet resultSet;
    protected PreparedStatement preparedStatement;
    protected List<String> preparedSql;
    protected AppUserRepository appUserRepository;
    protected FileStorageService fileStorageService;
    protected RedisTenantDataPurgeService redisTenantDataPurgeService;
    protected PlatformTenantDeletionAuditService deletionAuditService;
    protected PlatformTenantDeletionLog deletionLog;
    protected TenantDataErasureService service;
    protected Tenant tenant;

    @BeforeEach
    void setUp() throws SQLException {
        tenantRepository = mock(TenantRepository.class);
        keycloakAdminClient = mock(KeycloakAdminClient.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        resultSet = mock(ResultSet.class);
        appUserRepository = mock(AppUserRepository.class);
        fileStorageService = mock(FileStorageService.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        // Infra audit fix (2026-07-27) - eraseCurrentTenant now also enumerates
        // object-storage keys via statement.executeQuery(...) (see
        // TenantDataErasureService#eraseObjectKeysFromTable) before dropping
        // the schema; an empty result set by default keeps every existing
        // test in this class focused on what it already asserts.
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        when(appUserRepository.findByTenantId(any())).thenReturn(List.of());
        // Erasure completeness (2026-10-09): control-plane cleanup statements go
        // through prepareStatement, captured here so tests can assert on them
        // without disturbing the DROP-statement counts on `statement` above.
        preparedStatement = mock(PreparedStatement.class);
        preparedSql = new java.util.ArrayList<>();
        when(connection.prepareStatement(anyString()))
                .thenAnswer(
                        inv -> {
                            preparedSql.add(inv.getArgument(0));
                            return preparedStatement;
                        });

        tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.PREMIUM,
                        SCHEMA_NAME,
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(tenant));
        when(keycloakAdminClient.listUsersByTenant(SCHEMA_NAME)).thenReturn(List.of());

        redisTenantDataPurgeService = mock(RedisTenantDataPurgeService.class);
        deletionAuditService = mock(PlatformTenantDeletionAuditService.class);
        deletionLog = new PlatformTenantDeletionLog(tenant.getId(), "Acme", "admin-1");
        when(deletionAuditService.recordInitiated(any(), any(), any())).thenReturn(deletionLog);

        service =
                new TenantDataErasureService(
                        tenantRepository,
                        keycloakAdminClient,
                        dataSource,
                        appUserRepository,
                        fileStorageService,
                        redisTenantDataPurgeService,
                        deletionAuditService);

        TenantContext.set(TenantContext.of(SCHEMA_NAME, "user-123", Set.of("tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    protected Tenant tenantWithSchema(String name, String schema) {
        Tenant t =
                new Tenant(
                        name,
                        TenantPlan.FREE,
                        schema,
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.SIMPLE);
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        return t;
    }
}
