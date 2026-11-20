package com.agenticai.core.platform;

import com.agenticai.core.platform.DemoSandboxReclaimService.ReclaimableSandbox;
import com.agenticai.core.platform.DemoSandboxReclaimService.SharedSandbox;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The raw {@code control_plane.tenant} SQL behind {@link DemoSandboxReclaimService}. */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Repository
@Profile("security")
public class DemoSandboxReclaimJdbcRepository {

    private static final String RECLAIMABLE_WHERE =
            " FROM control_plane.tenant t WHERE t.is_demo AND t.status <> 'ERASED' AND"
                    + " t.schema_name <> ?";

    private final JdbcTemplate jdbc;

    public DemoSandboxReclaimJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The tenant row registered under the shared sandbox schema, if any. */
    public List<SharedSandbox> findShared(String sharedSchema) {
        return jdbc.query(
                "SELECT id, name, schema_name FROM control_plane.tenant WHERE schema_name =" + " ?",
                (rs, i) ->
                        new SharedSandbox(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getString("schema_name")),
                sharedSchema);
    }

    /** Oldest-first reclaimable demo sandboxes (never the shared one), at most {@code limit}. */
    public List<ReclaimableSandbox> listReclaimable(String sharedSchema, int limit) {
        return jdbc.query(
                "SELECT t.id, t.name, t.schema_name, t.status, t.created_at, s.last_at FROM"
                        + " control_plane.tenant t LEFT JOIN (SELECT tenant_id,"
                        + " MAX(last_activity_at) AS last_at FROM control_plane.demo_session"
                        + " GROUP BY tenant_id) s ON s.tenant_id = t.id WHERE t.is_demo AND"
                        + " t.status <> 'ERASED' AND t.schema_name <> ? ORDER BY t.created_at"
                        + " LIMIT "
                        + limit,
                (rs, i) ->
                        new ReclaimableSandbox(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getString("schema_name"),
                                rs.getString("status"),
                                instant(rs.getTimestamp("created_at")),
                                instant(rs.getTimestamp("last_at"))),
                sharedSchema);
    }

    public long countReclaimable(String sharedSchema) {
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*)" + RECLAIMABLE_WHERE, Long.class, sharedSchema);
        return count == null ? 0L : count;
    }

    /** Ids of the oldest reclaimable sandboxes, at most {@code limit}. */
    public List<UUID> oldestReclaimableIds(String sharedSchema, int limit) {
        return jdbc.queryForList(
                "SELECT t.id" + RECLAIMABLE_WHERE + " ORDER BY t.created_at LIMIT " + limit,
                UUID.class,
                sharedSchema);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
