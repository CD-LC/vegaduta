package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * real documented incident behind both branches it guards (see the class's own javadoc: an orphaned
 * Keycloak login for a dropped tenant schema, and later a platform/per-tenant kill switch). Covers,
 * in priority order: the platform-wide kill switch short-circuit, per-tenant
 * ACTIVE+not-emergency-stopped, an untracked tenant's schema-existence fallback, the legacy
 * "public" schema never being a usable tenant, and every fail path this class documents (missing
 * kill-switch row fails open; the schema check fails CLOSED; "public" is rejected before any
 * lookup) - a fail-open bug here silently locks out (or worse, silently admits) every request for
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
        guard =
                new TenantStatusGuardImpl(
                        tenantRepository, platformKillSwitchRepository, dataSource, Duration.ZERO);
        // Default: no kill-switch row at all - the common case in every test below that isn't
        // specifically exercising the kill switch itself.
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID))
                .thenReturn(Optional.empty());
    }

    private static Tenant activeTenant() {
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.PREMIUM,
                        SCHEMA_NAME,
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        tenant.markActive();
        return tenant;
    }

    @Test
    void platformKillSwitchStoppedBlocksEveryTenantRegardlessOfItsOwnStatus() {
        setUp();
        PlatformKillSwitch killSwitch = new PlatformKillSwitch();
        killSwitch.stop("Incident #42", "platform-admin-1");
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID))
                .thenReturn(Optional.of(killSwitch));
        when(tenantRepository.findBySchemaName(SCHEMA_NAME))
                .thenReturn(Optional.of(activeTenant()));

        assertFalse(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void platformKillSwitchPresentButNotStoppedFallsThroughToThePerTenantCheck() {
        setUp();
        PlatformKillSwitch killSwitch = new PlatformKillSwitch();
        // never .stop()'d - isStopped() is false by default
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID))
                .thenReturn(Optional.of(killSwitch));
        when(tenantRepository.findBySchemaName(SCHEMA_NAME))
                .thenReturn(Optional.of(activeTenant()));

        assertTrue(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void missingKillSwitchRowFailsOpenNotBlockingEveryTenant() {
        setUp();
        // platformKillSwitchRepository.findById(...) already stubbed to Optional.empty() in setUp()
        when(tenantRepository.findBySchemaName(SCHEMA_NAME))
                .thenReturn(Optional.of(activeTenant()));

        assertTrue(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void activeNonEmergencyStoppedTenantIsUsable() {
        setUp();
        when(tenantRepository.findBySchemaName(SCHEMA_NAME))
                .thenReturn(Optional.of(activeTenant()));

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
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        SCHEMA_NAME,
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
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
    void aSqlExceptionDuringTheSchemaExistenceCheckFailsClosed() throws SQLException {
        setUp();
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.empty());
        when(dataSource.getConnection()).thenThrow(new SQLException("connection pool exhausted"));

        assertFalse(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void theLegacyPublicSchemaIsNeverAUsableTenant() {
        setUp();
        // A validly signed token with tenant_id=public must NOT route to the legacy schema: the
        // schemaExists fallback would return true (Postgres always has "public"). The guard
        // rejects it before any lookup - even if a (broken) tenant row were to exist for it.
        when(tenantRepository.findBySchemaName(SchemaNameSanitizer.DEFAULT_SCHEMA))
                .thenReturn(Optional.of(activeTenant()));

        assertFalse(guard.isUsable(SchemaNameSanitizer.DEFAULT_SCHEMA));
        verify(tenantRepository, org.mockito.Mockito.never())
                .findBySchemaName(SchemaNameSanitizer.DEFAULT_SCHEMA);
    }

    @Test
    void everyNonCanonicalSpellingOfAnExistingSchemaIsRejected() throws SQLException {
        setUp();
        // "acme_co" is a real, active tenant; none of its lossy variants may reach it.
        Tenant acme = new Tenant("Acme", TenantPlan.PREMIUM, "acme_co", "agentic-ai", AccountType.B2C, ExperienceMode.ADVANCED);
        acme.markActive();
        when(tenantRepository.findBySchemaName("acme_co")).thenReturn(Optional.of(acme));
        stubSchemaExistsQuery(true);

        assertTrue(guard.isUsable("acme_co"));
        for (String variant : new String[] {"Acme-Co", "ACME_CO", "acme.co", "acme co", "Acme_Co", " acme_co", "acme_co "}) {
            assertFalse(guard.isUsable(variant), variant);
        }
    }

    // --- the failure flag is persisted on the tenant row, so it is shape/pod independent ---

    @Test
    void aFailureRecordedByAnotherPodOrShapeIsSeenFromAFreshRegistry() throws SQLException {
        setUp();
        // Model the control_plane.tenant row as shared state behind two independent registries
        // (two JVMs): each has its own empty in-memory set, only the "database" is common.
        SharedFlagDb db = new SharedFlagDb();
        TenantMigrationFailures migratingPod = new TenantMigrationFailures(db.dataSource());
        TenantMigrationFailures servingPod = new TenantMigrationFailures(db.dataSource());
        guard = new TenantStatusGuardImpl(tenantRepository, platformKillSwitchRepository, dataSource,
            Duration.ZERO, servingPod);
        when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(activeTenant()));

        assertTrue(guard.isUsable(SCHEMA_NAME));
        migratingPod.recordFailure(SCHEMA_NAME, "boom\non two lines");
        assertTrue(db.failedSchemas.contains(SCHEMA_NAME));
        assertTrue(servingPod.snapshot().contains(SCHEMA_NAME), "the retry shape must see it too");
        assertFalse(guard.isUsable(SCHEMA_NAME), "the serving pod must refuse a tenant another pod marked failed");
        assertFalse(db.lastError.contains("\n"));

        migratingPod.recordSuccess(SCHEMA_NAME);
        assertFalse(db.failedSchemas.contains(SCHEMA_NAME));
        assertTrue(guard.isUsable(SCHEMA_NAME));
    }

    @Test
    void theStoredErrorIsBoundedAndSingleLine() {
        assertTrue(TenantMigrationFailures.boundedError("x".repeat(5000)).length() <= TenantMigrationFailures.MAX_ERROR_LENGTH);
        assertEquals("a b", TenantMigrationFailures.boundedError("a\r\nb"));
    }

    @Test
    void aMissingFlagColumnDuringARollingDeployDoesNotLockEveryoneOut() throws SQLException {
        setUp();
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("column does not exist", "42703"));
        TenantMigrationFailures failures = new TenantMigrationFailures(broken);
        assertFalse(failures.hasFailed(SCHEMA_NAME));
        assertTrue(failures.snapshot().isEmpty());
    }

    @Test
    void anyOtherErrorReadingTheFlagFailsClosed() throws SQLException {
        setUp();
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused", "08001"));
        assertTrue(new TenantMigrationFailures(broken).hasFailed(SCHEMA_NAME));
    }

    /** A one-column stand-in for control_plane.tenant, driven through real JDBC calls on mocks. */
    private static final class SharedFlagDb {
        final java.util.Set<String> failedSchemas = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(true);
        volatile String lastError = "";

        DataSource dataSource() throws SQLException {
            DataSource ds = mock(DataSource.class);
            when(ds.getConnection()).thenAnswer(inv -> connection());
            return ds;
        }

        private Connection connection() throws SQLException {
            Connection c = mock(Connection.class);
            when(c.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> statement(inv.getArgument(0, String.class)));
            return c;
        }

        private PreparedStatement statement(String sql) throws SQLException {
            PreparedStatement ps = mock(PreparedStatement.class);
            java.util.List<Object> params = new java.util.ArrayList<>();
            org.mockito.Mockito.doAnswer(inv -> {
                int idx = inv.getArgument(0, Integer.class) - 1;
                while (params.size() <= idx) {
                    params.add(null);
                }
                params.set(idx, inv.getArgument(1));
                return null;
            }).when(ps).setObject(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(inv -> {
                params.clear();
                params.add(inv.getArgument(1));
                return null;
            }).when(ps).setString(org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.anyString());
            when(ps.executeUpdate()).thenAnswer(inv -> {
                if (sql.contains("migration_failed_at = now()")) {
                    lastError = String.valueOf(params.get(0));
                    failedSchemas.add(String.valueOf(params.get(1)));
                } else {
                    failedSchemas.remove(String.valueOf(params.get(0)));
                }
                return 1;
            });
            ResultSet rs = mock(ResultSet.class);
            when(ps.executeQuery()).thenAnswer(inv -> {
                if (sql.startsWith("SELECT 1")) {
                    when(rs.next()).thenReturn(failedSchemas.contains(String.valueOf(params.get(0))));
                } else {
                    java.util.Iterator<String> it = new java.util.ArrayList<>(failedSchemas).iterator();
                    String[] cur = new String[1];
                    when(rs.next()).thenAnswer(x -> {
                        if (it.hasNext()) {
                            cur[0] = it.next();
                            return true;
                        }
                        return false;
                    });
                    when(rs.getString(1)).thenAnswer(x -> cur[0]);
                }
                return rs;
            });
            return ps;
        }
    }

    // --- the per-request caching itself (the reason this class has a TTL at
    // all is "2-3 DB round-trips on every authenticated request") ---

    @Test
    void aPositiveTtlCachesTheTenantLookupSoTheFilterChainPaysItOnce() {
        setUp();
        guard =
                new TenantStatusGuardImpl(
                        tenantRepository,
                        platformKillSwitchRepository,
                        dataSource,
                        Duration.ofMinutes(1));
        when(tenantRepository.findBySchemaName(SCHEMA_NAME))
                .thenReturn(Optional.of(activeTenant()));

        assertTrue(guard.isUsable(SCHEMA_NAME));
        assertTrue(guard.isUsable(SCHEMA_NAME));
        assertTrue(guard.isUsable(SCHEMA_NAME));

        verify(tenantRepository, times(1)).findBySchemaName(SCHEMA_NAME);
        verify(platformKillSwitchRepository, times(1)).findById(PlatformKillSwitch.SINGLETON_ID);
    }

    @Test
    void anEvictedTenantAnswerIsReReadOnTheVeryNextCall() {
        setUp();
        guard =
                new TenantStatusGuardImpl(
                        tenantRepository,
                        platformKillSwitchRepository,
                        dataSource,
                        Duration.ofMinutes(1));
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
        guard =
                new TenantStatusGuardImpl(
                        tenantRepository,
                        platformKillSwitchRepository,
                        dataSource,
                        Duration.ofMinutes(1));
        Tenant stopped =
                new Tenant(
                        "Beta",
                        TenantPlan.FREE,
                        "beta",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        stopped.markActive();
        stopped.emergencyStop("chargeback storm");
        when(tenantRepository.findBySchemaName(SCHEMA_NAME))
                .thenReturn(Optional.of(activeTenant()));
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
        when(connection.prepareStatement(
                        "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(schemaFound);
    }
}
