package com.agenticai.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Cold-start lane: {@code app.tenancy.migrate-skip-up-to-date}. The skip must only ever skip work Flyway
 * would have found to be a no-op, must fall back to the full Flyway path on any doubt, and with the flag off
 * (the default) must not even look at the history table.
 */
class TenantSchemaMigratorSkipTest {

    // ---- version parsing (pure) ---------------------------------------------------------------------

    @Test
    void parsesPlainVersionedScriptNamesTheWayFlywayStoresThem() {
        Set<String> versions = TenantSchemaMigrator.parseTenantVersions(
            List.of("V1__init.sql", "V415__currency_varchar_fix.sql", "V007__leading_zeros.sql", "V1_2__dotted.sql"));

        assertThat(versions).containsExactlyInAnyOrder("1", "415", "7", "1.2");
    }

    @Test
    void anyUnrecognisedScriptNameSwitchesTheOptimisationOff() {
        assertThat(TenantSchemaMigrator.parseTenantVersions(List.of("V1__ok.sql", "R__repeatable.sql"))).isEmpty();
        assertThat(TenantSchemaMigrator.parseTenantVersions(List.of("V1__ok.sql", "README.sql"))).isEmpty();
        assertThat(TenantSchemaMigrator.parseTenantVersions(List.of("V1__ok.sql", "V2_no_double_underscore.sql"))).isEmpty();
        assertThat(TenantSchemaMigrator.parseTenantVersions(java.util.Arrays.asList("V1__ok.sql", null))).isEmpty();
    }

    @Test
    void normalizesVersionsConsistentlyForFileNamesAndHistoryRows() {
        assertThat(TenantSchemaMigrator.normalizeVersion("415")).isEqualTo("415");
        assertThat(TenantSchemaMigrator.normalizeVersion("0415")).isEqualTo("415");
        assertThat(TenantSchemaMigrator.normalizeVersion("1_2")).isEqualTo("1.2");
        assertThat(TenantSchemaMigrator.normalizeVersion("1.2")).isEqualTo("1.2");
        assertThat(TenantSchemaMigrator.normalizeVersion("not-a-number")).isEqualTo("not-a-number");
    }

    // ---- isUpToDate against a mocked database --------------------------------------------------------

    /** A DataSource whose history-table probe answers {@code hasHistory} and whose history rows are {@code applied}. */
    private static DataSource dataSource(boolean hasHistory, String... applied) throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet probe = mock(ResultSet.class);
        Statement stmt = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(probe);
        when(probe.next()).thenReturn(hasHistory);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rows);
        Boolean[] more = new Boolean[applied.length + 1];
        for (int i = 0; i < applied.length; i++) {
            more[i] = Boolean.TRUE;
        }
        more[applied.length] = Boolean.FALSE;
        when(rows.next()).thenReturn(more[0], java.util.Arrays.copyOfRange(more, 1, more.length));
        if (applied.length > 0) {
            when(rows.getString(1)).thenReturn(applied[0], java.util.Arrays.copyOfRange(applied, 1, applied.length));
        }
        return ds;
    }

    private static TenantSchemaMigrator migratorExpecting(DataSource ds, String... expected) {
        TenantSchemaMigrator migrator = spy(new TenantSchemaMigrator(ds));
        doReturn(Set.of(expected)).when(migrator).expectedTenantVersions();
        return migrator;
    }

    @Test
    void upToDateWhenEveryExpectedVersionIsRecordedAsApplied() throws Exception {
        TenantSchemaMigrator migrator = migratorExpecting(dataSource(true, "1", "2", "3"), "1", "2");

        assertThat(migrator.isUpToDate("tenant_a")).isTrue();
    }

    @Test
    void notUpToDateWhenAnExpectedVersionIsMissingFromTheHistory() throws Exception {
        TenantSchemaMigrator migrator = migratorExpecting(dataSource(true, "1", "2"), "1", "2", "3");

        assertThat(migrator.isUpToDate("tenant_a")).isFalse();
    }

    @Test
    void notUpToDateWhenTheSchemaHasNoHistoryTableYet() throws Exception {
        TenantSchemaMigrator migrator = migratorExpecting(dataSource(false), "1");

        assertThat(migrator.isUpToDate("brand_new_tenant")).isFalse();
    }

    @Test
    void notUpToDateAndNeverThrowsWhenTheDatabaseFails() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));
        TenantSchemaMigrator migrator = migratorExpecting(ds, "1");

        // Must degrade to "run Flyway", never surface the failure (and log only once).
        assertThat(migrator.isUpToDate("tenant_a")).isFalse();
        assertThat(migrator.isUpToDate("tenant_b")).isFalse();
    }

    @Test
    void notUpToDateWithoutTouchingTheDatabaseWhenTheClasspathScanCannotDecide() {
        DataSource ds = mock(DataSource.class);
        TenantSchemaMigrator migrator = migratorExpecting(ds /* no expected versions: scan found nothing usable */);

        assertThat(migrator.isUpToDate("tenant_a")).isFalse();
        verifyNoInteractions(ds);
    }

    // ---- migrate(): flag wiring ----------------------------------------------------------------------

    @Test
    void flagOnAndUpToDateSkipsFlywayEntirelyAndCountsTheSkip() {
        TenantSchemaMigrator migrator = spy(new TenantSchemaMigrator(mock(DataSource.class)));
        ReflectionTestUtils.setField(migrator, "skipUpToDate", true);
        doReturn(true).when(migrator).isUpToDate("t1");

        migrator.migrate("t1");

        verify(migrator, never()).buildFlyway(anyString());
        assertThat(migrator.skippedUpToDate()).isEqualTo(1);
    }

    @Test
    void flagOnButNotUpToDateFallsBackToTheFullFlywayMigrate() {
        TenantSchemaMigrator migrator = spy(new TenantSchemaMigrator(mock(DataSource.class)));
        ReflectionTestUtils.setField(migrator, "skipUpToDate", true);
        doReturn(false).when(migrator).isUpToDate("t1");
        Flyway flyway = mock(Flyway.class);
        doReturn(flyway).when(migrator).buildFlyway("t1");

        migrator.migrate("t1");

        verify(flyway).migrate();
        assertThat(migrator.skippedUpToDate()).isZero();
    }

    @Test
    void flagOffNeverConsultsTheHistoryTableAndAlwaysRunsFlywayAsBefore() {
        TenantSchemaMigrator migrator = spy(new TenantSchemaMigrator(mock(DataSource.class)));
        // skipUpToDate left at its default (false) - exactly what a plain `new TenantSchemaMigrator(ds)` has.
        doReturn(true).when(migrator).isUpToDate("t1");
        Flyway flyway = mock(Flyway.class);
        doReturn(flyway).when(migrator).buildFlyway("t1");

        migrator.migrate("t1");

        verify(migrator, never()).isUpToDate(anyString());
        verify(flyway).migrate();
        assertThat(migrator.skippedUpToDate()).isZero();
    }
}
