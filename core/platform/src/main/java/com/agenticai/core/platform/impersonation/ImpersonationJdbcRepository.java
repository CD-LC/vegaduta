package com.agenticai.core.platform.impersonation;

import com.agenticai.core.platform.impersonation.ImpersonationService.SessionItem;
import com.agenticai.core.security.ImpersonationGrantResolver.ViewAsSession;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The raw {@code control_plane.impersonation_session} SQL behind {@link ImpersonationService}: no
 * business rules, grant handling or caching live here.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Repository
@Profile("security")
public class ImpersonationJdbcRepository {

    static final String SELECT_COLUMNS =
            "s.id, s.admin_keycloak_id, s.admin_email, s.tenant_id, s.tenant_schema, "
                    + "s.target_keycloak_id, s.target_email, s.target_roles, s.reason, s.grant_hash, s.started_at, s.expires_at, "
                    + "s.ended_at, s.request_count, t.name AS tenant_name";
    static final String FROM =
            " FROM control_plane.impersonation_session s "
                    + "LEFT JOIN control_plane.tenant t ON t.id = s.tenant_id";
    static final String SQL_BY_HASH = "SELECT " + SELECT_COLUMNS + FROM + " WHERE s.grant_hash = ?";
    static final String SQL_BY_ID = "SELECT " + SELECT_COLUMNS + FROM + " WHERE s.id = ?";
    static final String SQL_LOCK_ADMIN = "SELECT pg_advisory_xact_lock(hashtext(?))";
    static final String SQL_COUNT_ACTIVE_FOR_ADMIN =
            "SELECT COUNT(*) FROM control_plane.impersonation_session "
                    + "WHERE admin_keycloak_id = ? AND ended_at IS NULL AND expires_at > ?";
    static final String SQL_INSERT =
            "INSERT INTO control_plane.impersonation_session (id, admin_keycloak_id, "
                    + "admin_email, tenant_id, tenant_schema, target_keycloak_id, target_email, target_roles, reason, grant_hash, "
                    + "started_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String SQL_END =
            "UPDATE control_plane.impersonation_session SET ended_at = ? "
                    + "WHERE id = ? AND ended_at IS NULL";
    static final String SQL_FLUSH_REQUESTS =
            "UPDATE control_plane.impersonation_session "
                    + "SET request_count = request_count + ?, last_request_at = GREATEST(COALESCE(last_request_at, ?), ?) "
                    + "WHERE id = ?";
    static final String ACTIVE_PREDICATE = " WHERE s.ended_at IS NULL AND s.expires_at > ?";
    static final String SQL_LIST_ACTIVE =
            "SELECT "
                    + SELECT_COLUMNS
                    + FROM
                    + ACTIVE_PREDICATE
                    + " ORDER BY s.started_at DESC LIMIT ? OFFSET ?";
    static final String SQL_LIST_ALL =
            "SELECT " + SELECT_COLUMNS + FROM + " ORDER BY s.started_at DESC LIMIT ? OFFSET ?";
    static final String SQL_COUNT_ACTIVE =
            "SELECT COUNT(*) FROM control_plane.impersonation_session s" + ACTIVE_PREDICATE;
    static final String SQL_COUNT_ALL = "SELECT COUNT(*) FROM control_plane.impersonation_session";

    /** Full row (internal). */
    record Row(
            UUID id,
            String adminKeycloakId,
            String adminEmail,
            UUID tenantId,
            String tenantSchema,
            String tenantName,
            String targetKeycloakId,
            String targetEmail,
            List<String> targetRoles,
            String reason,
            String grantHash,
            Instant startedAt,
            Instant expiresAt,
            Instant endedAt,
            int requestCount) {

        ViewAsSession toSession() {
            return new ViewAsSession(
                    id,
                    adminKeycloakId,
                    adminEmail,
                    tenantId,
                    tenantSchema,
                    tenantName,
                    targetKeycloakId,
                    targetEmail,
                    targetRoles,
                    expiresAt,
                    endedAt);
        }

        SessionItem toItem() {
            return new SessionItem(
                    id,
                    tenantId,
                    tenantName,
                    targetKeycloakId,
                    targetEmail,
                    adminEmail,
                    reason,
                    startedAt,
                    expiresAt,
                    endedAt,
                    requestCount);
        }
    }

    private final JdbcTemplate jdbc;

    public ImpersonationJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes the per-admin transaction-scoped advisory lock (call inside a transaction). */
    void lockAdmin(String adminKeycloakId) {
        jdbc.query(SQL_LOCK_ADMIN, rs -> null, adminKeycloakId);
    }

    long countActiveForAdmin(String adminKeycloakId, Instant now) {
        Long active =
                jdbc.queryForObject(
                        SQL_COUNT_ACTIVE_FOR_ADMIN,
                        Long.class,
                        adminKeycloakId,
                        Timestamp.from(now));
        return active == null ? 0L : active;
    }

    @SuppressWarnings("java:S107")
    void insert(
            UUID id,
            String adminKeycloakId,
            String adminEmail,
            UUID tenantId,
            String tenantSchema,
            String targetKeycloakId,
            String targetEmail,
            String targetRoles,
            String reason,
            String grantHash,
            Instant startedAt,
            Instant expiresAt) {
        jdbc.update(
                SQL_INSERT,
                id,
                adminKeycloakId,
                adminEmail,
                tenantId,
                tenantSchema,
                targetKeycloakId,
                targetEmail,
                targetRoles,
                reason,
                grantHash,
                Timestamp.from(startedAt),
                Timestamp.from(expiresAt));
    }

    /** Ends the session if still open; returns the rows updated (0 = already ended). */
    int end(UUID id, Instant endedAt) {
        return jdbc.update(SQL_END, Timestamp.from(endedAt), id);
    }

    List<Row> list(boolean activeOnly, Instant now, int limit, int offset) {
        return activeOnly
                ? jdbc.query(SQL_LIST_ACTIVE, ROW_MAPPER, Timestamp.from(now), limit, offset)
                : jdbc.query(SQL_LIST_ALL, ROW_MAPPER, limit, offset);
    }

    long count(boolean activeOnly, Instant now) {
        Long total =
                activeOnly
                        ? jdbc.queryForObject(SQL_COUNT_ACTIVE, Long.class, Timestamp.from(now))
                        : jdbc.queryForObject(SQL_COUNT_ALL, Long.class);
        return total == null ? 0L : total;
    }

    List<Row> findByGrantHash(String hash) {
        return jdbc.query(SQL_BY_HASH, ROW_MAPPER, hash);
    }

    Optional<Row> findById(UUID id) {
        return jdbc.query(SQL_BY_ID, ROW_MAPPER, id).stream().findFirst();
    }

    void flushRequests(UUID id, long count, Instant last) {
        jdbc.update(SQL_FLUSH_REQUESTS, count, Timestamp.from(last), Timestamp.from(last), id);
    }

    static final RowMapper<Row> ROW_MAPPER =
            (ResultSet rs, int n) ->
                    new Row(
                            rs.getObject("id", UUID.class),
                            rs.getString("admin_keycloak_id"),
                            rs.getString("admin_email"),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("tenant_schema"),
                            rs.getString("tenant_name"),
                            rs.getString("target_keycloak_id"),
                            rs.getString("target_email"),
                            roles(rs.getString("target_roles")),
                            rs.getString("reason"),
                            rs.getString("grant_hash"),
                            instant(rs, "started_at"),
                            instant(rs, "expires_at"),
                            instant(rs, "ended_at"),
                            rs.getInt("request_count"));

    private static List<String> roles(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
