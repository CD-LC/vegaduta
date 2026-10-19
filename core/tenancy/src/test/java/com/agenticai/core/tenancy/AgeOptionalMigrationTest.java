package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;

/**
 * Apache AGE is optional: V44 is skipped (recorded) only for an "age" extension error, nothing
 * else.
 */
class AgeOptionalMigrationTest {

    private static MigrationInfo pending(String version) {
        MigrationInfo info = mock(MigrationInfo.class);
        when(info.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(info.getDescription()).thenReturn("graph memory");
        return info;
    }

    private static FlywayException failure(String sqlMessage) {
        return new FlywayException(
                "Migration V44__graph_memory.sql failed", new SQLException(sqlMessage, "0A000"));
    }

    private static TenantSchemaMigrator migratorWhoseCatalog(Boolean ageListed)
            throws SQLException {
        DataSource ds = mock(DataSource.class);
        if (ageListed == null) {
            when(ds.getConnection()).thenThrow(new SQLException("connection refused"));
            return new TenantSchemaMigrator(ds);
        }
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT 1 FROM pg_available_extensions WHERE name = 'age'"))
                .thenReturn(rs);
        when(rs.next()).thenReturn(ageListed);
        return new TenantSchemaMigrator(ds);
    }

    @Test
    void theShapeCheckNeedsV44AndFeatureNotSupported() {
        assertTrue(
                TenantSchemaMigrator.isGraphMemoryFeatureFailure(
                        failure("ERROR: extension \"age\" is not available"), pending("44")));
        // Language independent: the SQLSTATE decides, not the message text.
        assertTrue(
                TenantSchemaMigrator.isGraphMemoryFeatureFailure(
                        failure("erreur localisee"), pending("44")));
        // permission denied is 42501, never a skip candidate whatever the message says.
        assertFalse(
                TenantSchemaMigrator.isGraphMemoryFeatureFailure(
                        new FlywayException(
                                "x",
                                new SQLException(
                                        "permission denied to create extension \"age\"", "42501")),
                        pending("44")));
        assertFalse(
                TenantSchemaMigrator.isGraphMemoryFeatureFailure(
                        failure("ERROR: extension \"age\" is not available"), pending("45")));
        assertFalse(TenantSchemaMigrator.isGraphMemoryFeatureFailure(failure("boom"), null));
    }

    @Test
    void anExtensionTheServerDoesNotOfferIsSkipped() throws SQLException {
        assertTrue(
                migratorWhoseCatalog(false)
                        .isAgeUnavailableFailure(
                                failure("ERROR: extension \"age\" is not available"),
                                pending("44")));
    }

    @Test
    void anExtensionTheServerOffersButRefusedIsNotHiddenForGood() throws SQLException {
        // Managed services that list AGE but have not allow-listed it for this user raise a
        // refusal, not
        // 'not available'. Recording V44 as applied would leave graph memory silently missing for
        // good.
        assertFalse(
                migratorWhoseCatalog(true)
                        .isAgeUnavailableFailure(
                                failure("ERROR: extension \"age\" is not allow-listed for users"),
                                pending("44")));
    }

    @Test
    void whenTheCatalogCannotBeReadNothingIsSkipped() throws SQLException {
        assertFalse(
                migratorWhoseCatalog(null)
                        .isAgeUnavailableFailure(
                                failure("ERROR: extension \"age\" is not available"),
                                pending("44")));
    }

    @Test
    void startupStrategyRecordsV44AndContinuesWhenAgeIsMissing() {
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        MigrationInfo v44 = pending("44");
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new MigrationInfo[] {v44});
        Configuration config = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(config);
        when(flyway.migrate())
                .thenThrow(failure("ERROR: extension \"age\" is not available"))
                .thenReturn(null);
        when(migrator.isAgeUnavailableFailure(any(), any())).thenReturn(true);
        doNothing().when(migrator).recordGraphMemorySkipped(any(), any());

        new AgeTolerantFlywayMigrationStrategy(migrator).migrate(flyway);

        verify(migrator).recordGraphMemorySkipped(eq("public"), eq(v44));
        verify(flyway, times(2)).migrate();
    }

    @Test
    void startupStrategyRethrowsEveryOtherFailure() {
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        MigrationInfo v44 = pending("44");
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new MigrationInfo[] {v44});
        FlywayException other = failure("ERROR: syntax error at or near \"x\"");
        when(flyway.migrate()).thenThrow(other);

        FlywayException thrown =
                assertThrows(
                        FlywayException.class,
                        () -> new AgeTolerantFlywayMigrationStrategy(migrator).migrate(flyway));

        assertSame(other, thrown);
        verify(migrator, never()).recordGraphMemorySkipped(any(), any());
    }

    @Test
    void aReplicaThatLostTheRaceToRecordV44JustRetries() {
        // Instance A recorded V44 between B's failed attempt and B's info() lookup: B sees V45 as
        // the first pending migration, so the old code rethrew the original AGE error.
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        MigrationInfo v44Applied = pending("44");
        when(v44Applied.getState()).thenReturn(MigrationState.SUCCESS);
        MigrationInfo v45 = pending("45");
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new MigrationInfo[] {v45});
        when(info.all()).thenReturn(new MigrationInfo[] {v44Applied, v45});
        when(flyway.migrate())
                .thenThrow(failure("ERROR: extension \"age\" is not available"))
                .thenReturn(null);

        new AgeTolerantFlywayMigrationStrategy(migrator).migrate(flyway);

        verify(flyway, times(2)).migrate();
        verify(migrator, never()).recordGraphMemorySkipped(any(), any());
    }

    @Test
    void theLostRaceCheckNeedsV44AppliedAndTheFeatureFailureShape() {
        MigrationInfo applied = pending("44");
        when(applied.getState()).thenReturn(MigrationState.SUCCESS);
        MigrationInfo stillPending = pending("44");
        when(stillPending.getState()).thenReturn(MigrationState.PENDING);
        FlywayException age = failure("ERROR: extension \"age\" is not available");

        assertTrue(
                TenantSchemaMigrator.isGraphMemorySkippedByAnotherInstance(
                        age, new MigrationInfo[] {applied}));
        assertFalse(
                TenantSchemaMigrator.isGraphMemorySkippedByAnotherInstance(
                        age, new MigrationInfo[] {stillPending}));
        assertFalse(
                TenantSchemaMigrator.isGraphMemorySkippedByAnotherInstance(
                        new FlywayException("x", new SQLException("boom", "42601")),
                        new MigrationInfo[] {applied}));
        assertFalse(TenantSchemaMigrator.isGraphMemorySkippedByAnotherInstance(age, null));
    }

    @Test
    void recordingV44IsIdempotentAndRetriesAPrimaryKeyClash() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(any())).thenReturn(ps);
        when(ps.executeUpdate())
                .thenThrow(new SQLException("duplicate key (installed_rank)", "23505"))
                .thenReturn(0);
        MigrationInfo v44 = pending("44");
        when(v44.getChecksum()).thenReturn(7);
        when(v44.getScript()).thenReturn("V44__graph_memory.sql");

        new TenantSchemaMigrator(ds).recordGraphMemorySkipped("public", v44);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conn, times(2)).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("WHERE NOT EXISTS"), sql.getValue());
        verify(ps, times(2)).executeUpdate();
    }

    @Test
    void defaultSchemaFallsBackToTheFirstSchemaThenPublic() {
        Flyway flyway = mock(Flyway.class);
        Configuration config = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(config);
        when(config.getSchemas()).thenReturn(new String[] {"app"});
        assertEquals("app", AgeTolerantFlywayMigrationStrategy.defaultSchema(flyway));
        when(config.getDefaultSchema()).thenReturn("tenant_x");
        assertEquals("tenant_x", AgeTolerantFlywayMigrationStrategy.defaultSchema(flyway));
    }
}
