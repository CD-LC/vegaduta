package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Pins down {@code TenantSchemaMigrator#isAlreadyExistsFailure} - the
 * predicate that decides whether a failed tenant migration gets auto-recorded
 * into {@code flyway_schema_history} as "already applied" without ever having
 * run. This is the single riskiest decision in the class: a regression that
 * BROADENS the match (e.g. dropping the "already exists" message check, or
 * matching outside SQLSTATE class 42) silently marks unrun migrations as
 * applied. The tenant's schema is then permanently missing tables, every
 * later migration for that version is skipped forever, and the first symptom
 * is an unrelated query throwing {@code relation does not exist} months later
 * - not a migration failure anyone would notice at deploy time. The negative
 * cases here (class-42 without the message, non-42 with the message) are
 * therefore the load-bearing ones.
 *
 * <p>The predicate is private and side-effect-free, so it is invoked here via
 * reflection rather than by widening its visibility - this suite must not
 * touch main source (it was written in a parallel-agent session with the
 * migrator's own file owned elsewhere). If the method is ever made
 * package-private for testability, {@link #isAlreadyExistsFailure} below
 * collapses to a direct call and nothing else changes.
 *
 * <p>{@code migrate()}'s Flyway/JDBC orchestration - including the
 * MAX_REPAIR_ATTEMPTS ceiling - is deliberately out of scope: Flyway is
 * constructed statically inside the method and issues real SQL against a real
 * DataSource, which no test in this module is allowed to have (see the
 * module-wide no-live-infrastructure rule).
 */
class TenantSchemaMigratorTest {

    /**
     * The class under test never touches its DataSource on this code path -
     * the predicate is a pure walk of an exception's cause chain.
     */
    private final TenantSchemaMigrator migrator = new TenantSchemaMigrator(mock(DataSource.class));

    /** Reflection shim for the private predicate - see class javadoc for why. */
    private boolean isAlreadyExistsFailure(Throwable e) {
        try {
            Method predicate =
                TenantSchemaMigrator.class.getDeclaredMethod("isAlreadyExistsFailure", Throwable.class);
            predicate.setAccessible(true);
            return (boolean) predicate.invoke(migrator, e);
        } catch (InvocationTargetException ex) {
            throw new AssertionError("isAlreadyExistsFailure threw - it must be a pure predicate", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(
                "isAlreadyExistsFailure(Throwable) no longer exists with this signature - "
                    + "if it was renamed or made package-private, update this shim to match", ex);
        }
    }

    /** The shape Flyway actually produces: FlywayException wrapping the driver's SQLException. */
    private static FlywayException flywayWrapping(String message, String sqlState) {
        return new FlywayException("Migration V12__agent_allowed_tool.sql failed",
            new SQLException(message, sqlState));
    }

    // -- The three SQLSTATEs the repair was built for (each hit in production
    // -- by a historically manually-backfilled schema, per the class javadoc) --

    @Test
    void duplicateTable42P07WithAlreadyExistsMessageIsRecognised() {
        // V10/V12's real failure mode: CREATE TABLE against a schema whose
        // table was created by hand via psql before the migration existed.
        assertTrue(isAlreadyExistsFailure(
            flywayWrapping("ERROR: relation \"knowledge_chunks\" already exists", "42P07")));
    }

    @Test
    void duplicateColumn42701WithAlreadyExistsMessageIsRecognised() {
        // V13's real failure mode: ALTER TABLE ... ADD COLUMN gets a
        // different SQLSTATE than CREATE TABLE for the same underlying
        // "backfilled outside Flyway" situation.
        assertTrue(isAlreadyExistsFailure(
            flywayWrapping("ERROR: column \"pairing_code\" of relation \"channel_session_pairing\" already exists", "42701")));
    }

    @Test
    void duplicateObject42710WithAlreadyExistsMessageIsRecognised() {
        // Constraints/types land on duplicate_object - the broad-class match
        // exists precisely so this next object kind doesn't need its own fix.
        assertTrue(isAlreadyExistsFailure(
            flywayWrapping("ERROR: constraint \"uq_agent_name\" for relation \"agent\" already exists", "42710")));
    }

    @Test
    void anyOtherClass42CodeWithAlreadyExistsMessageIsAlsoRecognised() {
        // duplicate_function 42723 - never seen live yet, but the predicate's
        // stated design is "any class-42 + the message", so pin that the
        // class-wide match really is class-wide and not a hidden enum of the
        // three codes above.
        assertTrue(isAlreadyExistsFailure(
            flywayWrapping("ERROR: function \"touch_updated_at\" already exists with same argument types", "42723")));
    }

    // -- The negative cases: these are the ones that keep the auto-repair
    // -- from swallowing genuinely-failed migrations --

    @Test
    void syntaxError42601WithoutAlreadyExistsMessageIsNotRecognisedAndSoRethrows() {
        // A typo'd migration script is class 42 too (42601). If the predicate
        // ever stops requiring the message match, this broken migration gets
        // recorded as applied and the schema is permanently missing whatever
        // the script was supposed to create.
        assertFalse(isAlreadyExistsFailure(
            flywayWrapping("ERROR: syntax error at or near \"TABEL\"", "42601")));
    }

    @Test
    void insufficientPrivilege42501IsNotRecognised() {
        // Same class-42 trap from the other direction: a permissions problem
        // must surface as a failed deploy, never as a phantom "applied" row.
        assertFalse(isAlreadyExistsFailure(
            flywayWrapping("ERROR: permission denied for schema tenant_acme", "42501")));
    }

    @Test
    void nonClass42FailureIsNotRecognisedEvenIfItsMessageSaysAlreadyExists() {
        // 23505 unique_violation - a data problem, not a DDL-object problem.
        // The message is crafted to contain the magic fragment on purpose:
        // this proves the predicate requires BOTH conditions, not just the
        // string match (the easier half to accidentally keep in a refactor).
        assertFalse(isAlreadyExistsFailure(
            flywayWrapping("ERROR: duplicate key - a row with this id already exists", "23505")));
    }

    @Test
    void sqlExceptionWithNullSqlStateIsNotRecognised() {
        // Driver-level failures (connection reset, timeout wrappers) often
        // carry no SQLSTATE at all - startsWith on it would NPE if the null
        // guard were dropped, and matching them would be wrong anyway.
        assertFalse(isAlreadyExistsFailure(
            new FlywayException("Migration failed", new SQLException("connection reset - already exists?", (String) null))));
    }

    @Test
    void sqlExceptionWithNullMessageIsNotRecognised() {
        // The mirror-image null guard: right class, no message to match on.
        assertFalse(isAlreadyExistsFailure(
            new FlywayException("Migration failed", new SQLException((String) null, "42P07"))));
    }

    @Test
    void nonSqlExceptionCauseChainIsNeverRecognisedEvenWithTheMagicWords() {
        // "already exists" appearing in some arbitrary RuntimeException's
        // message must not trigger the repair - only a real SQLException
        // carrying a Postgres SQLSTATE is evidence of the backfill situation.
        assertFalse(isAlreadyExistsFailure(
            new FlywayException("wrapper", new IllegalStateException("bean 'tenantMigrator' already exists"))));
    }

    // -- Chain-walking and message normalisation --

    @Test
    void matchIsFoundWhenTheSqlExceptionIsNestedSeveralCauseLevelsDeep() {
        // Flyway (and the connection pool under it) routinely wraps the
        // driver's SQLException two or three layers down - the predicate
        // walks getCause() rather than only inspecting the direct cause.
        FlywayException deeplyNested = new FlywayException(
            "Migration V12 failed",
            new RuntimeException("statement execution failed",
                new RuntimeException("jdbc wrapper",
                    new SQLException("ERROR: relation \"agent_allowed_tool\" already exists", "42P07"))));
        assertTrue(isAlreadyExistsFailure(deeplyNested));
    }

    @Test
    void messageMatchIsCaseInsensitive() {
        // The implementation lowercases before matching - a driver or locale
        // that shouts must not defeat the repair for the situation it was
        // built for.
        assertTrue(isAlreadyExistsFailure(
            flywayWrapping("ERROR: RELATION \"KNOWLEDGE_CHUNKS\" ALREADY EXISTS", "42P07")));
    }

    @Test
    void topLevelExceptionWithNoCauseAtAllIsNotRecognised() {
        // A bare FlywayException (validation failure, checksum mismatch)
        // carries no SQLException anywhere - nothing to repair.
        assertFalse(isAlreadyExistsFailure(new FlywayException("Validate failed: checksum mismatch for V9")));
    }

    // ------------------------------------------------------------------
    // isV352ProviderCredentialConstraintDrift - the V352 pre-V217 drift
    // signature: pending V352 + SQLSTATE 42P10 + the ON CONFLICT message.
    // Every narrowing guard is load-bearing: a broader match would repair-
    // retry failures that are not this drift at all.
    // ------------------------------------------------------------------

    private static MigrationInfo pendingVersion(String version) {
        MigrationInfo info = mock(MigrationInfo.class);
        when(info.getVersion()).thenReturn(
            version == null ? null : MigrationVersion.fromVersion(version));
        return info;
    }

    private static FlywayException v352Failure(String message) {
        return new FlywayException("Migration V352 failed",
            new RuntimeException("jdbc wrapper",
                new SQLException(message, "42P10")));
    }

    @Test
    void v352With42P10AndOnConflictMessageIsDrift() {
        assertTrue(migrator.isV352ProviderCredentialConstraintDrift(
            v352Failure("ERROR: there is no unique or exclusion constraint matching the ON CONFLICT specification"),
            pendingVersion("352")));
    }

    @Test
    void v351With42P10AndOnConflictMessageIsNotDrift() {
        // The repair is scoped to exactly V352 - the same error text on any
        // other pending version must not trigger it.
        assertFalse(migrator.isV352ProviderCredentialConstraintDrift(
            v352Failure("ERROR: there is no unique or exclusion constraint matching the ON CONFLICT specification"),
            pendingVersion("351")));
    }

    @Test
    void v352WithWrongSqlStateIsNotDrift() {
        FlywayException wrongState = new FlywayException("Migration V352 failed",
            new SQLException("ERROR: there is no unique or exclusion constraint matching the ON CONFLICT specification",
                "23505"));
        assertFalse(migrator.isV352ProviderCredentialConstraintDrift(
            wrongState, pendingVersion("352")));
    }

    @Test
    void v352With42P10ButDifferentMessageIsNotDrift() {
        // 42P10 can mean other things (e.g. a bad column reference); only the
        // exact ON CONFLICT fragment identifies this drift.
        assertFalse(migrator.isV352ProviderCredentialConstraintDrift(
            v352Failure("ERROR: column \"nope\" does not exist"),
            pendingVersion("352")));
    }

    @Test
    void v352DriftRejectsNullPendingAndNullVersion() {
        FlywayException e = v352Failure(
            "ERROR: there is no unique or exclusion constraint matching the ON CONFLICT specification");
        assertFalse(migrator.isV352ProviderCredentialConstraintDrift(e, null));
        assertFalse(migrator.isV352ProviderCredentialConstraintDrift(e, pendingVersion(null)));
    }

    // ------------------------------------------------------------------
    // repairV352ProviderCredentialConstraint - JDBC-mocked; must refuse on
    // duplicate keys and otherwise issue the quoted CREATE UNIQUE INDEX on
    // one autoCommit connection.
    // ------------------------------------------------------------------

    private static DataSource dsReturning(ResultSet rs) throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        return ds;
    }

    @Test
    void repairCreatesQuotedUniqueIndexWhenNoDuplicates() throws SQLException {
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        DataSource ds = dsReturning(empty);
        Connection conn = ds.getConnection();
        Statement stmt = conn.createStatement();

        new TenantSchemaMigrator(ds).repairV352ProviderCredentialConstraint("ruf_duta");

        verify(conn).setAutoCommit(true);
        InOrder order = inOrder(stmt);
        order.verify(stmt).executeQuery(
            "SELECT provider_key, COUNT(*) FROM \"ruf_duta\".provider_credential "
                + "GROUP BY provider_key HAVING COUNT(*) > 1 LIMIT 1");
        order.verify(stmt).execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS \"uq_provider_credential_provider_key_v352_repair\" "
                + "ON \"ruf_duta\".provider_credential (provider_key)");
    }

    @Test
    void repairRefusesToRunDdlWhenDuplicateKeysExist() throws SQLException {
        ResultSet dup = mock(ResultSet.class);
        when(dup.next()).thenReturn(true);
        when(dup.getString(1)).thenReturn("TYPESAFE");
        DataSource ds = dsReturning(dup);
        Statement stmt = ds.getConnection().createStatement();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            new TenantSchemaMigrator(ds).repairV352ProviderCredentialConstraint("rakesh"));

        assertTrue(ex.getMessage().contains("rakesh"));
        assertTrue(ex.getMessage().contains("TYPESAFE"));
        verify(stmt, never()).execute(anyString());
    }

    @Test
    void repairRefusesSchemaNameThatIsNotAPlainIdentifier() throws SQLException {
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        DataSource ds = dsReturning(empty);
        Statement stmt = ds.getConnection().createStatement();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            new TenantSchemaMigrator(ds).repairV352ProviderCredentialConstraint("tenant\"quoted"));

        assertTrue(ex.getMessage().contains("tenant\"quoted"));
        verify(stmt, never()).executeQuery(anyString());
        verify(stmt, never()).execute(anyString());
    }
}
