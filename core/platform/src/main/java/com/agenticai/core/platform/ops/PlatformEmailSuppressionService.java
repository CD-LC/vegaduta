package com.agenticai.core.platform.ops;

import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cross-tenant view of the email suppression list for the platform console.
 *
 * <p>{@code email_suppression} is a TENANT table (tenant/V335): each workspace
 * keeps its own list (unsubscribe link, bounce, manual), written by
 * {@code EmailSuppressionService}. There is no platform-level list, so this
 * reads every registered (non-demo, non-erased) tenant schema - at most
 * {@link #MAX_TENANTS} schemas and {@link #MAX_ROWS_PER_TENANT} rows each, one
 * bounded query per schema - and merges them newest first. {@code source} is the
 * tenant the suppression belongs to (display name, schema as fallback);
 * {@code tenantId} carries the schema name.
 *
 * <p>Removing an address deletes it from every tenant's list, or from one tenant
 * when {@code tenantId} is given, and is audited in each affected tenant.
 * Schema names come only from {@code control_plane.tenant} rows and are quoted.
 */
@Service
public class PlatformEmailSuppressionService {

    private static final Logger log = LoggerFactory.getLogger(PlatformEmailSuppressionService.class);

    static final int MAX_TENANTS = 500;
    static final int MAX_ROWS_PER_TENANT = 2000;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final int QUERY_TIMEOUT_SECONDS = 5;

    /*
     * Constant SQL fragments. The only piece assembled at runtime between them is the tenant
     * schema identifier, validated and quoted by PlatformMigrationService.schemaIdentifier;
     * every value (search pattern, row limit, address) is a bound parameter.
     */
    private static final String SELECT_PREFIX = "SELECT email, reason, created_at FROM ";
    private static final String SELECT_SUFFIX =
        ".email_suppression WHERE email LIKE ? ESCAPE '\\' ORDER BY created_at DESC LIMIT ?";
    private static final String DELETE_PREFIX = "DELETE FROM ";
    private static final String DELETE_SUFFIX = ".email_suppression WHERE email = ?";

    public record SuppressionItem(String email, String reason, String source, Instant createdAt, String tenantId) {
    }

    public record SuppressionPage(List<SuppressionItem> items, int total) {
    }

    private final DataSource dataSource;
    private final TenantRepository tenantRepository;
    private final PlatformTenantAuditor auditor;

    public PlatformEmailSuppressionService(DataSource dataSource, TenantRepository tenantRepository,
                                           PlatformTenantAuditor auditor) {
        this.dataSource = dataSource;
        this.tenantRepository = tenantRepository;
        this.auditor = auditor;
    }

    /** @param page 1-based; {@code q} is a case-insensitive substring of the address. */
    public SuppressionPage list(String q, Integer page, Integer limit) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        List<SuppressionItem> all = new ArrayList<>();
        List<Tenant> tenants = tenants();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            for (Tenant t : tenants) {
                readTenant(conn, t, needle, all);
            }
        } catch (SQLException e) {
            log.warn("Email suppressions: could not open a connection ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The database is not reachable.");
        }
        all.sort(Comparator.comparing(SuppressionItem::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SuppressionItem::email));
        int from = Math.min((safePage - 1) * safeLimit, all.size());
        int to = Math.min(from + safeLimit, all.size());
        return new SuppressionPage(List.copyOf(all.subList(from, to)), all.size());
    }

    /**
     * Deletes the address from every tenant list (or only {@code tenantSchema}'s).
     *
     * @return how many tenant lists it was removed from
     * @throws ResponseStatusException 404 when no list held it
     */
    public int remove(String email, String tenantSchema, String actor) {
        if (email == null || email.isBlank() || email.length() > 320) {
            throw new IllegalArgumentException("A valid email address is required.");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        String onlySchema = tenantSchema == null || tenantSchema.isBlank() ? null : tenantSchema.trim();
        int removed = 0;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            for (Tenant t : tenants()) {
                if (onlySchema != null && !onlySchema.equals(t.getSchemaName())
                    && !onlySchema.equals(String.valueOf(t.getId()))) {
                    continue;
                }
                String schemaIdentifier;
                try {
                    schemaIdentifier = PlatformMigrationService.schemaIdentifier(t.getSchemaName());
                } catch (IllegalArgumentException e) {
                    log.warn("Email suppressions: tenant {} has a schema name that is not a plain identifier; skipped",
                        t.getId());
                    continue;
                }
                // The validated, quoted schema identifier is the only dynamic piece; the address is bound.
                String sql = DELETE_PREFIX + schemaIdentifier + DELETE_SUFFIX;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    ps.setString(1, normalized);
                    int n = ps.executeUpdate();
                    if (n > 0) {
                        removed++;
                        auditor.record(t.getSchemaName(), actor, "platform.email_suppression.remove",
                            "email_suppression", normalized, "Removed " + normalized + " from the suppression list");
                    }
                } catch (SQLException e) {
                    if (!"42P01".equals(e.getSQLState())) {
                        log.warn("Email suppressions: delete failed in schema {} ({})", t.getSchemaName(),
                            e.getClass().getSimpleName());
                    }
                }
            }
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The database is not reachable.");
        }
        if (removed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That address is not on any suppression list.");
        }
        return removed;
    }

    private List<Tenant> tenants() {
        return tenantRepository.findRosterExcludingDemo().stream()
            .filter(t -> t.getErasedAt() == null)
            .limit(MAX_TENANTS)
            .toList();
    }

    /**
     * One bounded query per schema. The statement always carries the same two placeholders
     * ({@code LIKE ?} and {@code LIMIT ?}) and both are always bound: with no search term the
     * pattern is {@code %}, which matches every (NOT NULL) address. The validated, quoted schema
     * identifier is the only dynamic piece of the SQL text.
     */
    private void readTenant(Connection conn, Tenant t, String needle, List<SuppressionItem> out) {
        String schemaIdentifier;
        try {
            schemaIdentifier = PlatformMigrationService.schemaIdentifier(t.getSchemaName());
        } catch (IllegalArgumentException e) {
            log.warn("Email suppressions: tenant {} has a schema name that is not a plain identifier; skipped",
                t.getId());
            return;
        }
        String sql = SELECT_PREFIX + schemaIdentifier + SELECT_SUFFIX;
        String pattern = needle == null ? "%" : "%" + escapeLike(needle) + "%";
        String source = t.getName() == null || t.getName().isBlank() ? t.getSchemaName() : t.getName();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setString(1, pattern);
            ps.setInt(2, MAX_ROWS_PER_TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp created = rs.getTimestamp(3);
                    out.add(new SuppressionItem(rs.getString(1), rs.getString(2), source,
                        created == null ? null : created.toInstant(), t.getSchemaName()));
                }
            }
        } catch (SQLException e) {
            // 42P01: schema predates V335 or was never migrated - nothing suppressed there.
            if (!"42P01".equals(e.getSQLState())) {
                log.warn("Email suppressions: read failed in schema {} ({})", t.getSchemaName(),
                    e.getClass().getSimpleName());
            }
        }
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
