package com.agenticai.core.tenancy;

import com.agenticai.core.security.HotpathCaches;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tenant schemas whose startup migration failed. {@link TenantStatusGuardImpl} treats them as not
 * usable (a stale schema serves wrong or 500ing queries) until a later successful migration of the
 * same schema clears the mark - see {@link TenantMigrationService}, which records and clears, and
 * {@link TenantMigrationRetryJob}, which retries.
 *
 * <p><b>The flag is persisted</b> on the control-plane tenant row ({@code migration_failed_at},
 * {@code migration_error}; migration controlplane/V690), not held per JVM: the shape that migrates
 * (API with migrate-on-boot, or the migrate-only Job) is usually not the shape that serves the
 * request (a second API pod, {@code core-jobs}, {@code core-worker}), and each of those reads the
 * same row through the guard's TTL cache. A local in-memory set stays as a fast path for the
 * recording pod (and is the whole mechanism when no {@link DataSource} is wired, as in unit tests);
 * every record/clear also evicts this pod's cached status answer so the change is visible on the
 * very next request here and after at most the cache TTL ({@code app.tenant.status-cache-ttl},
 * 10 s) elsewhere.
 *
 * <p>Persistence failures never mask the migration outcome: a write error is logged and the local
 * mark still applies. A read error fails closed (the tenant is refused) except when the V690
 * columns do not exist yet (SQLState 42703, a rolling deploy ahead of the migration), where the
 * persisted flag is simply absent.
 */
@Component
public class TenantMigrationFailures {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationFailures.class);

    static final int MAX_ERROR_LENGTH = 500;

    private static final String UNDEFINED_COLUMN = "42703";

    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final DataSource dataSource;

    /** In-memory only: unit tests, and any context without a control-plane database. */
    public TenantMigrationFailures() {
        this.dataSource = null;
    }

    @Autowired
    public TenantMigrationFailures(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void recordFailure(String schema) {
        recordFailure(schema, null);
    }

    /** @param error the failure reason; stored truncated to {@value #MAX_ERROR_LENGTH} characters */
    public void recordFailure(String schema, String error) {
        if (schema == null) {
            return;
        }
        failed.add(schema);
        persist(
                "UPDATE control_plane.tenant SET migration_failed_at = now(), migration_error = ?"
                        + " WHERE schema_name = ?",
                boundedError(error),
                schema);
        HotpathCaches.evictTenantStatus(schema);
    }

    public void recordSuccess(String schema) {
        if (schema == null) {
            return;
        }
        failed.remove(schema);
        persist(
                "UPDATE control_plane.tenant SET migration_failed_at = NULL, migration_error = NULL"
                        + " WHERE schema_name = ? AND migration_failed_at IS NOT NULL",
                schema);
        HotpathCaches.evictTenantStatus(schema);
    }

    /**
     * Whether the schema's last migration failed, on this pod or (through the persisted flag) on
     * any other. One indexed point read when a {@link DataSource} is wired - callers on the hot
     * path go through {@link TenantStatusGuardImpl}'s TTL cache.
     */
    public boolean hasFailed(String schema) {
        if (schema == null) {
            return false;
        }
        if (failed.contains(schema)) {
            return true;
        }
        if (dataSource == null) {
            return false;
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT 1 FROM control_plane.tenant"
                                        + " WHERE schema_name = ? AND migration_failed_at IS NOT NULL")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            if (UNDEFINED_COLUMN.equals(e.getSQLState())) {
                return false;
            }
            log.warn("Could not read the persisted migration-failure flag for a tenant - refusing it (fail-closed)", e);
            return true;
        }
    }

    /** Every schema currently marked failed: this pod's set plus the persisted flags of all pods. */
    public Set<String> snapshot() {
        Set<String> all = new LinkedHashSet<>(failed);
        if (dataSource != null) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT schema_name FROM control_plane.tenant"
                                            + " WHERE migration_failed_at IS NOT NULL");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all.add(rs.getString(1));
                }
            } catch (SQLException e) {
                if (!UNDEFINED_COLUMN.equals(e.getSQLState())) {
                    log.warn("Could not list persisted migration failures - using this pod's view only", e);
                }
            }
        }
        return Set.copyOf(all);
    }

    static String boundedError(String error) {
        if (error == null) {
            return null;
        }
        String oneLine = error.replaceAll("[\\r\\n]+", " ").trim();
        return oneLine.length() > MAX_ERROR_LENGTH ? oneLine.substring(0, MAX_ERROR_LENGTH) : oneLine;
    }

    private void persist(String sql, Object... params) {
        if (dataSource == null) {
            return;
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not persist the tenant migration-failure flag; this pod still honours it in memory", e);
        }
    }
}
