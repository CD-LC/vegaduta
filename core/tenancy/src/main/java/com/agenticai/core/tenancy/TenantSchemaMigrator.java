package com.agenticai.core.tenancy;

import java.io.IOException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The one place that knows how to run every tenant-scoped Flyway migration (the {@code
 * classpath:db/migration/tenant} location - never the control_plane-qualified one, see {@code
 * application.yml}'s Flyway comment) against a single Postgres schema. Extracted out of {@link
 * TenantProvisioningService}, which originally had this as a private method used only at signup
 * time for a brand-new schema - now also used by {@link ExistingTenantSchemaMigrationRunner} to
 * bring already-provisioned tenants forward when new migrations land after they were created.
 *
 * <p><b>The gap this closes:</b> before this class existed, a tenant's schema only ever got
 * migrated once, at signup. Every migration added to {@code tenant/} after that point (V15/V16/V17
 * for Knowledge Stage F1/F3c, as one real example - caught live running the E2E suite against a
 * tenant created earlier in this project's history) would silently never reach that tenant's schema
 * - the first sign of trouble would be a background job or an API call throwing {@code relation
 * "..." does not exist} months later, not a clear migration-failure at deploy time. {@code
 * flyway.migrate()} is idempotent (a no-op if the schema is already current) and safe to call
 * repeatedly, which is exactly what makes "just re-run it for every tenant on every boot" (see
 * {@link ExistingTenantSchemaMigrationRunner}) a correct, low-risk fix rather than a one-off manual
 * patch for whichever tenants happen to be broken today.
 *
 * <p><b>A second, related gap this now also closes:</b> V10's own comment documents that tenants
 * provisioned before that migration existed needed a *manual* backfill (creating {@code
 * knowledge_chunks} directly via psql, outside Flyway) to catch up. That manual step never wrote a
 * {@code flyway_schema_history} row, so once this class started running V10 for real against those
 * schemas, Postgres rejected the {@code CREATE TABLE} with SQLState {@code 42P07} ("relation
 * already exists") and Flyway rolled the whole migration back - which, worse, also blocks every
 * migration *after* V10 from ever running for that schema. This turned out not to be a
 * one-migration problem: V12's {@code agent_allowed_tool} (another {@code CREATE TABLE}, same
 * {@code 42P07}) and V13's {@code channel_session_pairing} (an {@code ALTER TABLE ... ADD COLUMN},
 * which Postgres instead rejects as {@code 42701} "duplicate_column" - a different SQLSTATE for the
 * same underlying "already backfilled outside Flyway" situation) both hit it independently. Rather
 * than enumerate every DDL-object-type-specific "already exists" SQLSTATE one at a time as more
 * turn up, {@link #isAlreadyExistsFailure} matches broadly: any SQLSTATE in class {@code 42}
 * (syntax error / access rule violation - where every Postgres "already exists" condition lives,
 * whatever kind of object it's about) whose message says "already exists". {@link
 * #recordAsAlreadyApplied} then reconciles the schema's history to match reality using Flyway's own
 * checksum for the untouched script, so tenants that already have a real, successful history row
 * for the same version keep validating correctly - instead of leaving the schema stuck. This
 * assumes a historically manually-backfilled object matches what its formal migration would have
 * created, which is the same assumption the backfill step itself was written under; it does not
 * attempt a structural diff.
 */
@Component
public class TenantSchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaMigrator.class);

    private static final String TENANT_MIGRATION_LOCATION = "classpath:db/migration/tenant";

    // Every Postgres "already exists" condition (duplicate_table 42P07,
    // duplicate_column 42701, duplicate_object 42710 for things like
    // constraints/types, duplicate_function 42723, etc.) lives in SQLSTATE
    // class 42 and always says "already exists" in its message - checking
    // the message instead of enumerating every specific code means a new
    // DDL statement type hitting this same historical-manual-backfill
    // problem (as V12 and V13 both did, independently, for two different
    // reasons) is handled automatically instead of needing its own fix.
    private static final String SQLSTATE_CLASS_SYNTAX_OR_ACCESS_RULE = "42";
    private static final String ALREADY_EXISTS_MESSAGE_FRAGMENT = "already exists";

    // V352 drift repair: schemas provisioned before V217's unique index on
    // provider_credential(provider_key) fail V352's ON CONFLICT (provider_key)
    // with SQLSTATE 42P10. This is the one narrowly-scoped case where the fix
    // is to create the missing index and RETRY the migration - never to record
    // it as applied without running it.
    private static final String V352 = "352";
    private static final String SQLSTATE_INVALID_COLUMN_REFERENCE = "42P10";
    private static final String MISSING_ON_CONFLICT_CONSTRAINT =
            "no unique or exclusion constraint matching the on conflict specification";
    private static final String V352_REPAIR_INDEX =
            "uq_provider_credential_provider_key_v352_repair";

    // Apache AGE is optional on the database server. V44 (graph memory) runs CREATE EXTENSION age,
    // which
    // managed Postgres services that do not offer AGE (AWS RDS/Aurora, Cloud SQL, AlloyDB) refuse.
    // Only for
    // that migration and only for an "age" extension error, V44 is recorded as applied without
    // running, so
    // the rest of the schema migrates and the platform runs without graph memory (drop the
    // graph-memory
    // profile). Recording it with Flyway's own checksum keeps validation green if AGE is installed
    // later;
    // the graph itself is then created with: SELECT ag_catalog.create_graph('<schema>_kg');
    static final String V44_GRAPH_MEMORY = "44";
    private static final String SQLSTATE_FEATURE_NOT_SUPPORTED = "0A000";
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    // Safety bound so a genuinely stuck schema fails loudly instead of
    // looping forever - no real tenant schema has anywhere near this many
    // migrations today.
    private static final int MAX_REPAIR_ATTEMPTS = 20;

    // --- cold-start lane: skip schemas that are already fully migrated
    // -----------------------------------
    //
    // Every boot re-runs Flyway for every ACTIVE tenant schema (see
    // ExistingTenantSchemaMigrationRunner). When
    // nothing is pending that is pure overhead: a new Flyway instance, a classpath scan of ~200
    // scripts,
    // checksum computation and validation per schema - about 1.35 s each in production (70 s for 52
    // schemas sequentially, per TenantMigrationGate's javadoc), all of it spent while readiness is
    // held.
    // With app.tenancy.migrate-skip-up-to-date=true a schema whose flyway_schema_history already
    // lists every
    // tenant migration on the classpath as successfully applied is skipped after two cheap queries.
    // Anything uncertain (no history table, an unparsable script name, a query error, an empty
    // classpath scan)
    // falls back to the full Flyway migrate, so the flag can only ever skip work Flyway would have
    // found to
    // be a no-op. Deliberate trade-off: an up-to-date schema is no longer checksum-validated on
    // boot (the
    // flyway-lint CI gate is what forbids editing a shipped migration). Default OFF = today's
    // behaviour.

    /**
     * Flyway's default naming for a versioned SQL migration; this project uses only plain
     * V{digits}__name.sql.
     */
    private static final Pattern TENANT_MIGRATION_FILE =
            Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.+\\.sql$");

    private final DataSource dataSource;

    @Value("${app.tenancy.migrate-skip-up-to-date:false}")
    private boolean skipUpToDate;

    private final AtomicInteger skippedUpToDate = new AtomicInteger();
    private final AtomicBoolean warnedUndecidable = new AtomicBoolean();
    private final AtomicBoolean warnedAgeUnavailable = new AtomicBoolean();
    private volatile Set<String> expectedVersionsCache;

    public TenantSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * How many schemas {@link #migrate} has skipped as already up to date (always 0 with the flag
     * off).
     */
    int skippedUpToDate() {
        return skippedUpToDate.get();
    }

    /**
     * Runs every not-yet-applied tenant-scoped migration against {@code schemaName}. Safe to call
     * on a schema that's already fully up to date (Flyway just finds nothing pending and returns
     * immediately) and safe to call on a schema that doesn't exist yet ({@code createSchemas(true)}
     * creates it) - both existing tenants (whose schema is more or less up to date) and Stage 5's
     * brand-new-signup path get identical behavior out of this one method.
     *
     * <p>If a migration fails specifically because the object(s) it tries to create already exist
     * (see class javadoc), this reconciles that one version's history entry and resumes with the
     * next pending migration, rather than leaving the schema stuck. Any other failure is rethrown
     * as-is.
     */
    public void migrate(String schemaName) {
        if (skipUpToDate && isUpToDate(schemaName)) {
            skippedUpToDate.incrementAndGet();
            log.debug(
                    "Schema '{}' already has every tenant migration applied - skipped without"
                            + " running Flyway",
                    schemaName);
            return;
        }
        for (int attempt = 0; attempt < MAX_REPAIR_ATTEMPTS; attempt++) {
            Flyway flyway = buildFlyway(schemaName);
            try {
                flyway.migrate();
                return;
            } catch (FlywayException e) {
                MigrationInfo[] pending = flyway.info().pending();
                if (pending.length > 0 && isV352ProviderCredentialConstraintDrift(e, pending[0])) {
                    repairV352ProviderCredentialConstraint(schemaName);
                    continue;
                }
                if (pending.length > 0 && isAgeUnavailableFailure(e, pending[0])) {
                    recordGraphMemorySkipped(schemaName, pending[0]);
                    continue;
                }
                if (pending.length > 0 && isAlreadyExistsFailure(e)) {
                    MigrationInfo culprit = pending[0];
                    log.warn(
                            "Schema '{}': migration {} ({}) failed because the object(s) it creates"
                                + " already exist - this schema was very likely backfilled manually"
                                + " outside Flyway before this migration was formalized"
                                + " (V10__knowledge_chunks.sql documents exactly this happening"
                                + " once already). Recording it as already-applied, using Flyway's"
                                + " own checksum for the unmodified script, and continuing with any"
                                + " migrations after it.",
                            schemaName,
                            culprit.getVersion(),
                            culprit.getDescription());
                    recordAsAlreadyApplied(schemaName, culprit);
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException(
                "Schema '"
                        + schemaName
                        + "' still has pending migrations after "
                        + MAX_REPAIR_ATTEMPTS
                        + " already-exists repair attempts - aborting to avoid looping forever;"
                        + " this needs manual investigation.");
    }

    /**
     * True only when it can be proven, cheaply, that Flyway would find nothing to do: the schema
     * has a {@code flyway_schema_history} and every tenant migration version found on the classpath
     * is recorded there as a successful SQL migration. Any doubt answers false (the caller then
     * runs the full Flyway path), so a failure here never blocks a migration - it logs once and
     * degrades to the old behaviour.
     */
    boolean isUpToDate(String schemaName) {
        try {
            Set<String> expected = expectedTenantVersions();
            if (expected.isEmpty()) {
                if (warnedUndecidable.compareAndSet(false, true)) {
                    log.warn(
                            "app.tenancy.migrate-skip-up-to-date is on but no usable list of tenant"
                                + " migrations was found on the classpath (none present, or an"
                                + " unrecognised script name) - running the full Flyway migrate for"
                                + " every schema (logged once)");
                }
                return false;
            }
            Set<String> applied = appliedVersions(schemaName);
            return applied != null && applied.containsAll(expected);
        } catch (SQLException | RuntimeException e) {
            if (warnedUndecidable.compareAndSet(false, true)) {
                log.warn(
                        "Could not decide whether tenant schemas are already up to date ({}) -"
                            + " running the full Flyway migrate for the schemas this cannot decide"
                            + " on (logged once)",
                        e.toString());
            }
            return false;
        }
    }

    /**
     * Versions of every tenant migration on the classpath, computed once. Empty means "cannot
     * tell".
     */
    public Set<String> expectedTenantVersions() {
        Set<String> cached = expectedVersionsCache;
        if (cached != null) {
            return cached;
        }
        List<String> fileNames = new ArrayList<>();
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver(
                                    TenantSchemaMigrator.class.getClassLoader())
                            .getResources("classpath*:db/migration/tenant/*.sql");
            for (Resource resource : resources) {
                fileNames.add(resource.getFilename());
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not scan the classpath for tenant migrations", e);
        }
        Set<String> computed = parseTenantVersions(fileNames);
        expectedVersionsCache = computed;
        return computed;
    }

    /**
     * Pure: normalized versions for a list of script file names, or an EMPTY set when any name is
     * not a plain {@code V<digits>__description.sql} (a repeatable or oddly named script could
     * change the maths, so the optimisation switches itself off rather than guess). Versions are
     * normalized the way Flyway stores them: parts split on '.' or '_', leading zeros dropped,
     * joined with '.'.
     */
    static Set<String> parseTenantVersions(Collection<String> fileNames) {
        Set<String> versions = new HashSet<>();
        for (String name : fileNames) {
            Matcher m = name == null ? null : TENANT_MIGRATION_FILE.matcher(name);
            if (m == null || !m.matches()) {
                return Set.of();
            }
            versions.add(normalizeVersion(m.group(1)));
        }
        return versions;
    }

    public static String normalizeVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String[] parts = raw.trim().split("[._]");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) {
                out.append('.');
            }
            try {
                out.append(new BigInteger(part));
            } catch (NumberFormatException e) {
                return raw; // not a number: leave it as-is so it simply fails to match
            }
        }
        return out.toString();
    }

    /**
     * Versions recorded as successfully applied SQL migrations in the schema's own history table,
     * or {@code null} when the schema has no history table yet (a brand-new tenant: full Flyway
     * path).
     */
    Set<String> appliedVersions(String schemaName) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            boolean hasHistory;
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND"
                                    + " table_name = 'flyway_schema_history'")) {
                ps.setString(1, schemaName);
                try (ResultSet rs = ps.executeQuery()) {
                    hasHistory = rs.next();
                }
            }
            if (!hasHistory) {
                return null;
            }
            Set<String> applied = new HashSet<>();
            String sql =
                    "SELECT version FROM "
                            + quoteIdentifier(schemaName)
                            + ".flyway_schema_history WHERE success = true AND type = 'SQL' AND"
                            + " version IS NOT NULL";
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    applied.add(normalizeVersion(rs.getString(1)));
                }
            }
            return applied;
        }
    }

    private boolean isAlreadyExistsFailure(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sqlEx
                    && sqlEx.getSQLState() != null
                    && sqlEx.getSQLState().startsWith(SQLSTATE_CLASS_SYNTAX_OR_ACCESS_RULE)
                    && sqlEx.getMessage() != null
                    && sqlEx.getMessage()
                            .toLowerCase(Locale.ROOT)
                            .contains(ALREADY_EXISTS_MESSAGE_FRAGMENT)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * True only when the pending migration is exactly V352 and the failure is Postgres's SQLSTATE
     * 42P10 "no unique or exclusion constraint matching the ON CONFLICT specification" - the
     * signature of a schema provisioned before V217 created the unique index on {@code
     * provider_credential(provider_key)}. Any other version, SQLSTATE, or message must not reach
     * the repair path.
     */
    boolean isV352ProviderCredentialConstraintDrift(Throwable failure, MigrationInfo pending) {
        if (pending == null
                || pending.getVersion() == null
                || !V352.equals(pending.getVersion().getVersion())) {
            return false;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && SQLSTATE_INVALID_COLUMN_REFERENCE.equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage()
                            .toLowerCase(Locale.ROOT)
                            .contains(MISSING_ON_CONFLICT_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recreates the V217-era unique index that V352's {@code ON CONFLICT (provider_key)} requires,
     * in schemas old enough to predate it. Refuses to run when duplicates already exist (a unique
     * index would fail anyway, and papering over dup keys is a data decision, not a schema repair)
     * - and never touches Flyway history: after this runs, {@code migrate()}'s loop retries and
     * V352 executes its unchanged script for real.
     */
    void repairV352ProviderCredentialConstraint(String schemaName) {
        String schema = quoteIdentifier(schemaName);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            String duplicateCheck =
                    "SELECT provider_key, COUNT(*) FROM "
                            + schema
                            + ".provider_credential "
                            + "GROUP BY provider_key HAVING COUNT(*) > 1 LIMIT 1";
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(duplicateCheck)) {
                if (rs.next()) {
                    throw new IllegalStateException(
                            "Schema '"
                                    + schemaName
                                    + "': refusing automatic V352 repair - "
                                    + "provider_credential already has duplicate provider_key '"
                                    + rs.getString(1)
                                    + "'; a unique index cannot be created "
                                    + "and deduplication is a data decision, not a schema repair. "
                                    + "Manual cleanup required.");
                }
            }
            String createIndex =
                    "CREATE UNIQUE INDEX IF NOT EXISTS "
                            + quoteIdentifier(V352_REPAIR_INDEX)
                            + " ON "
                            + schema
                            + ".provider_credential (provider_key)";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createIndex);
            }
            log.warn(
                    "Schema '{}': created unique index {} on provider_credential(provider_key) - "
                            + "a narrowly-scoped repair for pre-V217 historical drift so V352's "
                            + "ON CONFLICT can retry; the migration itself still runs unchanged.",
                    schemaName,
                    V352_REPAIR_INDEX);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed V352 drift repair (provider_credential unique index) for schema '"
                            + schemaName
                            + "' - manual repair needed",
                    e);
        }
    }

    /**
     * True when {@code pending} is V44 (graph memory), the failure carries SQLSTATE {@code 0A000}
     * (feature_not_supported, what {@code CREATE EXTENSION} raises for an extension that is not
     * there) and the server's own catalog confirms {@code age} is NOT available ({@code
     * pg_available_extensions}). The catalog query, not the message text, decides: it is language
     * independent, and it keeps a fixable problem from being hidden for good: an extension that IS
     * available but refused (not allow-listed on a managed service, permission denied) still fails
     * the migration so the operator fixes it, instead of V44 being recorded as applied and graph
     * memory silently missing.
     */
    boolean isAgeUnavailableFailure(Throwable failure, MigrationInfo pending) {
        return isGraphMemoryFeatureFailure(failure, pending) && ageNotAvailableOnServer();
    }

    /**
     * The cheap, static half: V44 plus a feature_not_supported SQLException somewhere in the cause
     * chain.
     */
    static boolean isGraphMemoryFeatureFailure(Throwable failure, MigrationInfo pending) {
        if (pending == null
                || pending.getVersion() == null
                || !V44_GRAPH_MEMORY.equals(pending.getVersion().getVersion())) {
            return false;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && SQLSTATE_FEATURE_NOT_SUPPORTED.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a V44 feature-not-supported failure is a loser of the startup race: another
     * instance already recorded V44 as applied between this instance's failed attempt and its
     * {@code info()} lookup, so V44 is no longer pending and the migration can simply be retried.
     * Only the failure shape and "V44 is applied now" are checked; the server-catalog decision was
     * made by whichever instance recorded it.
     */
    static boolean isGraphMemorySkippedByAnotherInstance(Throwable failure, MigrationInfo[] all) {
        if (all == null) {
            return false;
        }
        for (MigrationInfo info : all) {
            if (info.getVersion() != null
                    && V44_GRAPH_MEMORY.equals(info.getVersion().getVersion())
                    && info.getState() != null
                    && info.getState().isApplied()) {
                return isGraphMemoryFeatureFailure(failure, info);
            }
        }
        return false;
    }

    /**
     * True only when the server positively reports that no {@code age} extension can be created.
     */
    boolean ageNotAvailableOnServer() {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT 1 FROM pg_available_extensions WHERE name = 'age'")) {
            return !rs.next();
        } catch (SQLException | RuntimeException e) {
            // Cannot tell: never skip a migration on a guess.
            log.warn(
                    "Could not check pg_available_extensions for 'age' ({}); not skipping V44",
                    e.toString());
            return false;
        }
    }

    /**
     * Records V44 as applied without running it because the server has no Apache AGE (see {@link
     * #V44_GRAPH_MEMORY}). Public so the startup Flyway run ({@code
     * AgeTolerantFlywayMigrationStrategy}) applies the same rule to the default schema.
     */
    public void recordGraphMemorySkipped(String schemaName, MigrationInfo migration) {
        if (warnedAgeUnavailable.compareAndSet(false, true)) {
            log.warn(
                    "The database server does not provide the Apache AGE extension, so migration {}"
                        + " ({}) is recorded as applied without running and graph memory is"
                        + " unavailable. Remove the 'graph-memory' Spring profile (Helm:"
                        + " global.postgresql.age=false). Everything else works. Logged once; first"
                        + " schema: '{}'.",
                    migration.getVersion(),
                    migration.getDescription(),
                    schemaName);
        }
        recordAsAlreadyApplied(schemaName, migration);
    }

    /**
     * Validates, then renders, a schema/index name as a double-quoted SQL identifier via {@link
     * TenantSchemaGuard#quoteIdentifier} (the one choke point: {@link
     * TenantSchemaGuard#requirePlainIdentifier}). Every identifier that reaches this class's SQL -
     * a tenant row's schema_name, a generated {@code t_} name, the configured demo schema,
     * Flyway's default schema ({@code public} is legitimate here, so the guard's reserved-name
     * list is deliberately NOT applied), the V352 repair index - passes through here, so a name
     * outside {@link TenantSchemaGuard#PLAIN_IDENTIFIER} is refused before any statement is
     * built, never merely quoted.
     */
    private static String quoteIdentifier(String identifier) {
        return TenantSchemaGuard.quoteIdentifier(identifier);
    }

    private void recordAsAlreadyApplied(String schemaName, MigrationInfo migration) {
        Integer checksum = migration.getChecksum();
        if (checksum == null) {
            throw new IllegalStateException(
                    "Migration "
                            + migration.getVersion()
                            + " for schema '"
                            + schemaName
                            + "' has no checksum to record - not a plain SQL migration? Refusing to"
                            + " guess; needs manual repair.");
        }
        // Idempotent and safe against a concurrent instance: the catch in the startup strategy is
        // not under Flyway's advisory lock, so two replicas booting on a fresh AGE-less database
        // can both reach this. NOT EXISTS makes the second a no-op; a primary-key clash on
        // installed_rank (another writer took the same MAX+1 between our read and write) is
        // retried,
        // and the retry then sees the other row.
        String sql =
                "INSERT INTO "
                        + quoteIdentifier(schemaName)
                        + ".flyway_schema_history (installed_rank, version, description, type,"
                        + " script, checksum, installed_by, installed_on, execution_time, success)"
                        + " SELECT COALESCE(MAX(installed_rank), 0) + 1, ?, ?, 'SQL', ?, ?,"
                        + " current_user, now(), 0, true FROM "
                        + quoteIdentifier(schemaName)
                        + ".flyway_schema_history WHERE NOT EXISTS (SELECT 1 FROM "
                        + quoteIdentifier(schemaName)
                        + ".flyway_schema_history WHERE version = ? AND success)";
        for (int attempt = 1; ; attempt++) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(
                        true); // one INSERT, committed immediately whatever the pool does
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, migration.getVersion().getVersion());
                    ps.setString(2, migration.getDescription());
                    ps.setString(3, migration.getScript());
                    ps.setInt(4, checksum);
                    ps.setString(5, migration.getVersion().getVersion());
                    ps.executeUpdate();
                }
                return;
            } catch (SQLException e) {
                if (SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState()) && attempt < 3) {
                    continue;
                }
                throw new IllegalStateException(
                        "Failed to record migration "
                                + migration.getVersion()
                                + " as already-applied for schema '"
                                + schemaName
                                + "' - manual flyway_schema_history repair needed",
                        e);
            }
        }
    }

    Flyway buildFlyway(String schemaName) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(TENANT_MIGRATION_LOCATION)
                .schemas(schemaName)
                .createSchemas(true)
                // Same rationale as spring.flyway.out-of-order in
                // application.yml: controlplane/ and tenant/ migrations share
                // one version namespace, so a schema that already applied a
                // higher-numbered migration must still be able to pick up a
                // lower-numbered one that lands later - otherwise Flyway's
                // default validate fails the whole schema with "Detected
                // resolved migration not applied to database".
                .outOfOrder(true)
                .baselineOnMigrate(false)
                .load();
    }
}
