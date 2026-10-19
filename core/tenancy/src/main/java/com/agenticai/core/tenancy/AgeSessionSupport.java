package com.agenticai.core.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that prepares a JDBC session for Apache AGE calls - shared by
 * {@code GraphMemoryService} (graphmemory) and {@link TenantDataErasureService}.
 *
 * <p><b>Why not just {@code LOAD 'age'}.</b> Postgres only lets a superuser
 * {@code LOAD} a library from {@code $libdir}; a non-superuser role gets
 * {@code access to library "age" is not allowed}. Libraries under
 * {@code $libdir/plugins/} are the documented exception any role may load,
 * and the AGE image symlinks {@code age.so} there. So, in order:
 * <ol>
 *   <li>if {@code age} is listed in {@code shared_preload_libraries} or
 *       {@code session_preload_libraries}, it is already loaded in every
 *       backend - no {@code LOAD} at all (result cached per JVM; an
 *       unreadable setting, e.g. a role without {@code pg_read_all_settings},
 *       counts as "not preloaded");
 *   <li>otherwise {@code LOAD '$libdir/plugins/age'};
 *   <li>only if that fails, the legacy {@code LOAD 'age'} (superuser setups).
 * </ol>
 *
 * <p><b>search_path.</b> AGE's Cypher translation resolves operators such as
 * {@code agtype @> agtype} through {@code search_path}; without
 * {@code ag_catalog} on it, {@code MERGE} fails with "operator does not exist".
 * {@link #open} prepends {@code ag_catalog} (bind parameter via
 * {@code set_config}, never string-built) and {@link Session#close()} restores
 * the previous value, so a pooled connection is never handed back with a
 * mutated {@code search_path}.
 *
 * <p>Every probe/attempt that may fail runs under a savepoint when the
 * connection is inside a transaction, so a refused {@code LOAD} or
 * {@code SHOW} never poisons the caller's transaction.
 */
public final class AgeSessionSupport {

    private static final Logger log = LoggerFactory.getLogger(AgeSessionSupport.class);

    public static final String LOAD_PLUGINS = "LOAD '$libdir/plugins/age'";
    public static final String LOAD_LEGACY = "LOAD 'age'";
    static final String SHOW_SHARED_PRELOAD = "SHOW shared_preload_libraries";
    static final String SHOW_SESSION_PRELOAD = "SHOW session_preload_libraries";
    static final String CURRENT_SEARCH_PATH = "SELECT current_setting('search_path')";
    static final String SET_SEARCH_PATH = "SELECT set_config('search_path', ?, false)";
    private static final String AG_CATALOG = "ag_catalog";

    private static final AtomicReference<Boolean> PRELOADED = new AtomicReference<>();

    private AgeSessionSupport() {
        // utility class
    }

    /**
     * Makes AGE usable on {@code connection} (load if needed + {@code ag_catalog}
     * on {@code search_path}). Close the returned handle (try-with-resources)
     * to restore the previous {@code search_path}.
     */
    public static Session open(Connection connection) throws SQLException {
        loadIfNeeded(connection);
        String previous = currentSearchPath(connection);
        if (previous == null || containsAgCatalog(previous)) {
            return new Session(connection, null);
        }
        setSearchPath(connection, previous.isBlank() ? AG_CATALOG : AG_CATALOG + ", " + previous);
        return new Session(connection, previous);
    }

    /** Load step alone (no search_path change). */
    public static void loadIfNeeded(Connection connection) throws SQLException {
        if (isPreloaded(connection)) {
            return;
        }
        SQLException pluginsFailure;
        try {
            executeGuarded(connection, LOAD_PLUGINS);
            return;
        } catch (SQLException e) {
            pluginsFailure = e;
        }
        try {
            executeGuarded(connection, LOAD_LEGACY);
            log.debug("AGE loaded via legacy LOAD 'age' ($libdir/plugins path failed: {})", pluginsFailure.getMessage());
        } catch (SQLException legacyFailure) {
            legacyFailure.addSuppressed(pluginsFailure);
            throw legacyFailure;
        }
    }

    /** Test hook: the preload probe result is cached per JVM. */
    public static void resetCachedPreloadStateForTests() {
        PRELOADED.set(null);
    }

    static boolean isPreloaded(Connection connection) {
        Boolean cached = PRELOADED.get();
        if (cached != null) {
            return cached;
        }
        boolean preloaded = listsAge(showSetting(connection, SHOW_SHARED_PRELOAD))
            || listsAge(showSetting(connection, SHOW_SESSION_PRELOAD));
        PRELOADED.compareAndSet(null, preloaded);
        return preloaded;
    }

    /** {@code true} if a preload-libraries value lists AGE ({@code age}, {@code '$libdir/plugins/age'}, {@code age.so}, ...). */
    static boolean listsAge(String libraries) {
        if (libraries == null || libraries.isBlank()) {
            return false;
        }
        return Arrays.stream(libraries.split(","))
            .map(entry -> entry.trim().replace("'", "").replace("\"", ""))
            .map(entry -> entry.substring(entry.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT))
            .map(entry -> entry.endsWith(".so") ? entry.substring(0, entry.length() - 3) : entry)
            .anyMatch("age"::equals);
    }

    private static boolean containsAgCatalog(String searchPath) {
        return Arrays.stream(searchPath.split(","))
            .map(entry -> entry.trim().replace("\"", ""))
            .anyMatch(AG_CATALOG::equals);
    }

    private static String showSetting(Connection connection, String sql) {
        try {
            return queryScalarGuarded(connection, sql);
        } catch (SQLException | RuntimeException e) {
            log.debug("Could not read '{}' ({}) - assuming AGE is not preloaded", sql, e.getMessage());
            return null;
        }
    }

    private static String currentSearchPath(Connection connection) {
        try {
            return queryScalarGuarded(connection, CURRENT_SEARCH_PATH);
        } catch (SQLException | RuntimeException e) {
            log.debug("Could not read search_path ({}) - leaving it untouched", e.getMessage());
            return null;
        }
    }

    private static void setSearchPath(Connection connection, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SET_SEARCH_PATH)) {
            ps.setString(1, value);
            ps.execute();
        }
    }

    private static String queryScalarGuarded(Connection connection, String sql) throws SQLException {
        Savepoint savepoint = savepointIfInTransaction(connection);
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            String value = rs != null && rs.next() ? rs.getString(1) : null;
            release(connection, savepoint);
            return value;
        } catch (SQLException | RuntimeException e) {
            rollbackTo(connection, savepoint);
            throw e;
        }
    }

    private static void executeGuarded(Connection connection, String sql) throws SQLException {
        Savepoint savepoint = savepointIfInTransaction(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            release(connection, savepoint);
        } catch (SQLException e) {
            rollbackTo(connection, savepoint);
            throw e;
        }
    }

    private static Savepoint savepointIfInTransaction(Connection connection) throws SQLException {
        return connection.getAutoCommit() ? null : connection.setSavepoint();
    }

    private static void release(Connection connection, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            log.debug("Could not release savepoint ({})", e.getMessage());
        }
    }

    private static void rollbackTo(Connection connection, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            connection.rollback(savepoint);
        } catch (SQLException e) {
            log.debug("Could not roll back to savepoint ({})", e.getMessage());
        }
    }

    /** Restores the {@code search_path} {@link #open} replaced (no-op if it changed nothing). */
    public static final class Session implements AutoCloseable {

        private final Connection connection;
        private final String previousSearchPath;

        private Session(Connection connection, String previousSearchPath) {
            this.connection = connection;
            this.previousSearchPath = previousSearchPath;
        }

        @Override
        public void close() {
            if (previousSearchPath == null) {
                return;
            }
            try {
                setSearchPath(connection, previousSearchPath);
            } catch (SQLException | RuntimeException e) {
                // An aborted transaction rolls a session-level SET back on its
                // own; anything else is logged, never allowed to mask the
                // caller's real outcome.
                log.debug("Could not restore search_path after an AGE call ({})", e.getMessage());
            }
        }
    }
}
