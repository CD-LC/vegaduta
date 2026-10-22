package com.agenticai.core.platform.ops;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantSchemaGuard;
import com.agenticai.core.tenancy.TenantSchemaMigrator;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Migration status for the platform console - {@code GET /api/platform/migrations}.
 *
 * <ul>
 *   <li><b>controlPlane</b>: Spring's own {@link Flyway} bean ({@code info()}):
 *       current version, pending and failed counts.</li>
 *   <li><b>expectedTenantVersion</b>: the highest tenant migration on the
 *       classpath ({@link TenantSchemaMigrator#expectedTenantVersions()}).</li>
 *   <li><b>tenants</b>: every registered (non-demo, non-erased) tenant schema,
 *       ONE read-only query each against its own {@code flyway_schema_history}
 *       ({@link #MAX_TENANTS} at most, {@link #QUERY_TIMEOUT_SECONDS} s timeout
 *       each). A schema whose query fails reads {@code version=null},
 *       {@code status="error"} instead of failing the page.</li>
 * </ul>
 *
 * <p>Schema names come only from {@code control_plane.tenant} rows, never from
 * the request, and are quoted as identifiers. Result cached 30 s per replica.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class PlatformMigrationService {

    private static final Logger log = LoggerFactory.getLogger(PlatformMigrationService.class);

    static final int MAX_TENANTS = 500;
    static final int QUERY_TIMEOUT_SECONDS = 5;
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    public record MigrationCounts(String version, int pending, int failed) {
    }

    /** {@code status}: ok | behind | failed | no-history | error. */
    public record TenantMigration(String tenantId, String name, String schema, String version, int pending, int failed,
                                  String status) {
    }

    public record MigrationsResponse(MigrationCounts controlPlane, String expectedTenantVersion,
                                     List<TenantMigration> tenants) {
    }

    /** What one schema's history table says. {@code applied} holds normalized successful versions. */
    record SchemaHistory(String highestVersion, Set<String> applied, int failed) {
    }

    private final ObjectProvider<Flyway> flyway;
    private final ObjectProvider<DataSource> dataSource;
    private final TenantRepository tenantRepository;
    private final TenantSchemaMigrator tenantSchemaMigrator;
    private final Clock clock;

    private Instant cachedAt;
    private MigrationsResponse cached;

    @Autowired
    public PlatformMigrationService(ObjectProvider<Flyway> flyway, ObjectProvider<DataSource> dataSource,
                                    TenantRepository tenantRepository, TenantSchemaMigrator tenantSchemaMigrator) {
        this(flyway, dataSource, tenantRepository, tenantSchemaMigrator, Clock.systemUTC());
    }

    PlatformMigrationService(ObjectProvider<Flyway> flyway, ObjectProvider<DataSource> dataSource,
                             TenantRepository tenantRepository, TenantSchemaMigrator tenantSchemaMigrator, Clock clock) {
        this.flyway = flyway;
        this.dataSource = dataSource;
        this.tenantRepository = tenantRepository;
        this.tenantSchemaMigrator = tenantSchemaMigrator;
        this.clock = clock;
    }

    public synchronized MigrationsResponse status() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cachedAt.plus(CACHE_TTL))) {
            return cached;
        }
        Set<String> expected = expectedVersions();
        String expectedHighest = highest(expected);
        List<TenantMigration> tenants = new ArrayList<>();
        List<Tenant> roster = tenantRepository.findRosterExcludingDemo().stream()
            .filter(t -> t.getErasedAt() == null)
            .limit(MAX_TENANTS)
            .toList();
        DataSource ds = dataSource.getIfAvailable();
        if (ds != null && !roster.isEmpty()) {
            try (Connection conn = ds.getConnection()) {
                conn.setAutoCommit(true);
                conn.setReadOnly(true);
                for (Tenant t : roster) {
                    tenants.add(tenantRow(conn, t, expected));
                }
            } catch (SQLException e) {
                log.warn("Migration status: could not open a connection ({})", e.getClass().getSimpleName());
                roster.forEach(t -> tenants.add(errorRow(t)));
            }
        } else {
            roster.forEach(t -> tenants.add(errorRow(t)));
        }
        cached = new MigrationsResponse(controlPlane(), expectedHighest, List.copyOf(tenants));
        cachedAt = now;
        return cached;
    }

    MigrationCounts controlPlane() {
        Flyway f = flyway.getIfAvailable();
        if (f == null) {
            return new MigrationCounts(null, 0, 0);
        }
        try {
            MigrationInfoService info = f.info();
            MigrationInfo current = info.current();
            int failed = 0;
            for (MigrationInfo m : info.all()) {
                if (m.getState() == MigrationState.FAILED) {
                    failed++;
                }
            }
            return new MigrationCounts(current == null || current.getVersion() == null ? null
                : current.getVersion().getVersion(), info.pending().length, failed);
        } catch (RuntimeException e) {
            log.warn("Migration status: control-plane Flyway info failed ({})", e.getClass().getSimpleName());
            return new MigrationCounts(null, 0, 0);
        }
    }

    private TenantMigration tenantRow(Connection conn, Tenant t, Set<String> expected) {
        String schema = t.getSchemaName();
        try {
            SchemaHistory history = readHistory(conn, schema);
            return classify(t, history, expected);
        } catch (IllegalArgumentException e) {
            log.warn("Migration status: tenant {} has a schema name that is not a plain identifier; skipped", t.getId());
            return errorRow(t);
        } catch (SQLException e) {
            boolean missing = "42P01".equals(e.getSQLState()) || "3F000".equals(e.getSQLState());
            return new TenantMigration(String.valueOf(t.getId()), t.getName(), schema, null,
                missing ? expected.size() : 0, 0, missing ? "no-history" : "error");
        }
    }

    /**
     * Reads one schema's {@code flyway_schema_history}. The schema name is the only dynamic
     * piece of the statement; it is validated against {@link TenantSchemaGuard#PLAIN_IDENTIFIER}
     * and quoted by {@link #schemaIdentifier} (JDBC cannot bind an identifier), the rest is
     * constant SQL with no values at all.
     *
     * @throws IllegalArgumentException when {@code schema} is not a plain identifier
     */
    static SchemaHistory readHistory(Connection conn, String schema) throws SQLException {
        String sql = "SELECT version, success FROM " + schemaIdentifier(schema)
            + ".flyway_schema_history WHERE version IS NOT NULL";
        Set<String> applied = new HashSet<>();
        int failed = 0;
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    if (rs.getBoolean(2)) {
                        applied.add(TenantSchemaMigrator.normalizeVersion(rs.getString(1)));
                    } else {
                        failed++;
                    }
                }
            }
        }
        return new SchemaHistory(highest(applied), applied, failed);
    }

    static TenantMigration classify(Tenant t, SchemaHistory history, Set<String> expected) {
        int pending = 0;
        for (String v : expected) {
            if (!history.applied().contains(v)) {
                pending++;
            }
        }
        String status = history.failed() > 0 ? "failed" : (pending > 0 ? "behind" : "ok");
        return new TenantMigration(String.valueOf(t.getId()), t.getName(), t.getSchemaName(), history.highestVersion(),
            pending, history.failed(), status);
    }

    private static TenantMigration errorRow(Tenant t) {
        return new TenantMigration(String.valueOf(t.getId()), t.getName(), t.getSchemaName(), null, 0, 0, "error");
    }

    private Set<String> expectedVersions() {
        try {
            return tenantSchemaMigrator.expectedTenantVersions();
        } catch (RuntimeException e) {
            log.warn("Migration status: could not list tenant migrations on the classpath ({})", e.getClass().getSimpleName());
            return Set.of();
        }
    }

    /** Highest of a set of normalized versions ("452", "12.1"), compared numerically part by part; null when empty. */
    static String highest(Set<String> versions) {
        return versions.stream().max(VERSION_ORDER).orElse(null);
    }

    static final Comparator<String> VERSION_ORDER = (a, b) -> {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            BigInteger x = i < pa.length ? toNumber(pa[i]) : BigInteger.ZERO;
            BigInteger y = i < pb.length ? toNumber(pb[i]) : BigInteger.ZERO;
            int c = x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    };

    private static BigInteger toNumber(String part) {
        try {
            return new BigInteger(part);
        } catch (NumberFormatException e) {
            return BigInteger.ZERO;
        }
    }

    /**
     * Validates {@code schema} against {@link TenantSchemaGuard#PLAIN_IDENTIFIER} and returns it
     * as a double-quoted SQL identifier, via {@link TenantSchemaGuard#quoteIdentifier} (the one
     * validation choke point). This is the only way a tenant schema name reaches a SQL string in
     * this package.
     *
     * @throws IllegalArgumentException when the name is null or not a plain lowercase identifier
     */
    static String schemaIdentifier(String schema) {
        return TenantSchemaGuard.quoteIdentifier(schema);
    }
}
