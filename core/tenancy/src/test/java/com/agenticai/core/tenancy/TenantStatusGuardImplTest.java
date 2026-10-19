package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Zero test coverage existed for {@link TenantStatusGuardImpl} before this - the actual access
 * control decision behind every authenticated request (via {@code TenantContextFilter}), with a
 * real documented incident behind both branches it guards (see the class's own javadoc: an
 * orphaned Keycloak login for a dropped tenant schema, and later a platform/per-tenant kill
 * switch). Covers, in priority order: the platform-wide kill switch short-circuit, per-tenant
 * ACTIVE+not-emergency-stopped, an untracked tenant's schema-existence fallback, and every
 * fail-open path this class documents (missing kill-switch row, SQLException during the schema
 * check) - a fail-open bug here silently locks out (or worse, silently admits) every request for
 * an affected tenant, exactly the kind of defect a status guard needs direct coverage for.
 */
class TenantStatusGuardImplTest {

    private static final String SCHEMA_NAME = "acme";

    private TenantRepository tenantRepository;
    private PlatformKillSwitchRepository platformKillSwitchRepository;
    private DataSource dataSource;
    private TenantStatusGuardImpl guard;

    private void setUp() {
        tenantRepository = mock(TenantRepository.class);
        platformKillSwitchRepository = mock(PlatformKillSwitchRepository.class);
        dataSource = mock(DataSource.class);
        // Duration.ZERO = a pass-through cache (every call runs the real
        // lookup) - keeps each case's mock stubs the only source of truth,
        // exactly the pre-cache behavior these tests were written against.
        guard = new TenantStatusGuardImpl(tenantRepository, platformKillSwitchRepository, dataSource, Duration.ZERO);
        // Default: no kill-switch row at all - the common case in every test below that isn't
        // specifically exercising the kill switch itself.
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.empty());
    }

    private static Tenant activeTenant() {
        Tenant tenant = new Tenant("Acme", TenantPlan.PREMIUM, SCHEMA_NAME, "agentic-ai", AccountType.B2C, ExperienceMode.ADVANCED);
        tenant.markActive();
        return tenant;
    }

    @Test
    void platformKillSwitchStoppedBlocksEveryTenantRegardlessOfItsOwnStatus() {
        setUp();
        PlatformKillSwitch killSwitch = new PlatformKillSwitch();
        killSwitch.stop("Incident #42", "platform-admin-1");
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.of(killSwitch));
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(activeTenant()));

        assertFalse(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void platformKillSwitchPresentButNotStoppedFallsThroughToThePerTenantCheck() {
        setUp();
        PlatformKillSwitch killSwitch = new PlatformKillSwitch();
        // never .stop()'d - isStopped() is false by default
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.of(killSwitch));
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(activeTenant()));

        assertTrue(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void missingKillSwitchRowFailsOpenNotBlockingEveryTenant() {
        setUp();
        // platformKillSwitchRepository.findById(...) already stubbed to Optional.empty() in setUp()
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(activeTenant()));

        assertTrue(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void activeNonEmergencyStoppedTenantIsUsable() {
        setUp();
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(activeTenant()));

        assertTrue(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void emergencyStoppedTenantIsNotUsableEvenIfStillActive() {
        setUp();
        Tenant tenant = activeTenant();
        tenant.emergencyStop("Suspicious billing activity");
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(tenant));

        assertFalse(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void nonActiveTenantStatusIsNotUsable() {
        setUp();
        // Fresh Tenant() defaults to PROVISIONING - never markActive()'d.
        Tenant tenant = new Tenant("Acme", TenantPlan.FREE, SCHEMA_NAME, "agentic-ai", AccountType.B2C, ExperienceMode.ADVANCED);
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(tenant));

        assertFalse(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void untrackedTenantWithAnExistingSchemaFallsBackToUsable() throws SQLException {
        setUp();
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.empty());
        stubSchemaExistsQuery(true);

        assertTrue(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void untrackedTenantWithNoMatchingSchemaIsNotUsable() throws SQLException {
        setUp();
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.empty());
        stubSchemaExistsQuery(false);

        assertFalse(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void aSqlExceptionDuringTheSchemaExistenceCheckFailsOpen() throws SQLException {
        setUp();
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.empty());
        when(dataSource.getConnection()).thenThrow(new SQLException("connection pool exhausted"));

        assertTrue(guard.isUsable(SCHEMA_NAME));
    }

    // --- the per-request caching itself (the reason this class has a TTL at
    // all is "2-3 DB round-trips on every authenticated request") ---

    @Test
    void aPositiveTtlCachesTheTenantLookupSoTheFilterChainPaysItOnce() {
        setUp();
        guard = new TenantStatusGuardImpl(tenantRepository, platformKillSwitchRepository, dataSource, Duration.ofMinutes(1));
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(activeTenant()));

        assertTrue(guard.isUsable(SCHEMA_NAME));
        assertTrue(guard.isUsable(SCHEMA_NAME));
        assertTrue(guard.isUsable(SCHEMA_NAME));

        verify(tenantRepository, times(1)).findBySchemaName(SCHEMA_NAME);
        verify(platformKillSwitchRepository, times(1)).findById(PlatformKillSwitch.SINGLETON_ID);
    }

    @Test
    void anEvictedTenantAnswerIsReReadOnTheVeryNextCall() {
        setUp();
        guard = new TenantStatusGuardImpl(tenantRepository, platformKillSwitchRepository, dataSource, Duration.ofMinutes(1));
        Tenant stopped = activeTenant();
        stopped.emergencyStop("fraud signal");
        when(tenantRepository.findBySchemaName(SCHEMA_NAME))
            .thenReturn(Optional.of(activeTenant()))
            .thenReturn(Optional.of(stopped));

        assertTrue(guard.isUsable(SCHEMA_NAME));
        com.agenticai.core.security.HotpathCaches.evictTenantStatus(SCHEMA_NAME);
        // The emergency-stopped row wins on the re-read - the whole point of
        // wiring eviction into TenantPlanEvictionListener is that a stop
        // never sits behind a stale "usable" for up to the TTL.
        assertFalse(guard.isUsable(SCHEMA_NAME));
        verify(tenantRepository, times(2)).findBySchemaName(SCHEMA_NAME);
    }

    @Test
    void tenantAnswersAreIsolatedPerTenantKey() {
        setUp();
        guard = new TenantStatusGuardImpl(tenantRepository, platformKillSwitchRepository, dataSource, Duration.ofMinutes(1));
        Tenant stopped = new Tenant("Beta", TenantPlan.FREE, "beta", "agentic-ai", AccountType.B2C, ExperienceMode.ADVANCED);
        stopped.markActive();
        stopped.emergencyStop("chargeback storm");
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(activeTenant()));
        when(tenantRepository.findBySchemaName("beta")).thenReturn(Optional.of(stopped));

        assertTrue(guard.isUsable(SCHEMA_NAME));
        assertFalse(guard.isUsable("beta"));
        assertTrue(guard.isUsable(SCHEMA_NAME));

        verify(tenantRepository, times(1)).findBySchemaName(SCHEMA_NAME);
        verify(tenantRepository, times(1)).findBySchemaName("beta");
    }

    private void stubSchemaExistsQuery(boolean schemaFound) throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(schemaFound);
    }
}
