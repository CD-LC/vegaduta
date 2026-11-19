package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The raw {@code control_plane.demo_session} / {@code demo_channel_link} SQL behind {@link
 * DemoSandboxMetricsService}: catalog probes and live aggregates, no business logic. See the
 * service's javadoc for why this is plain JDBC rather than JPA.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Repository
public class DemoSandboxMetricsJdbcRepository {

    /** One operator-table row as stored; the service derives the status. */
    public record RecentRow(
            UUID id,
            UUID tenantId,
            String tenantName,
            String tenantSchema,
            String email,
            Instant createdAt,
            Instant expiresAt,
            Instant expiredAt,
            UUID convertedTenantId,
            Instant convertedAt,
            int messageCount) {}

    /**
     * One pass over the table for every scalar on the page.
     *
     * <p>The conversion counters are COHORT counters - {@code created_at >= ?} paired with {@code
     * converted_tenant_id IS NOT NULL} - so the rate's numerator and denominator describe the same
     * set of sandboxes. See {@link DemoMetricsWindow}'s javadoc for why that is the only definition
     * worth putting in front of an owner.
     *
     * <p>{@code converted_tenant_id IS NOT NULL} rather than {@code converted_at IS NOT NULL}: V126
     * writes both together, but the tenant id is the column that carries the actual conversion
     * evidence (which real tenant this sandbox became), so it is the one to test.
     */
    private static final String SUMMARY_SQL =
            """
        SELECT
            COUNT(*)                                                                          AS created_all,
            COUNT(*) FILTER (WHERE s.created_at >= ?)                                         AS created_today,
            COUNT(*) FILTER (WHERE s.created_at >= ?)                                         AS created_7d,
            COUNT(*) FILTER (WHERE s.created_at >= ?)                                         AS created_30d,
            COUNT(*) FILTER (WHERE s.converted_tenant_id IS NOT NULL)                         AS converted_all,
            COUNT(*) FILTER (WHERE s.converted_tenant_id IS NOT NULL AND s.created_at >= ?)   AS converted_today,
            COUNT(*) FILTER (WHERE s.converted_tenant_id IS NOT NULL AND s.created_at >= ?)   AS converted_7d,
            COUNT(*) FILTER (WHERE s.converted_tenant_id IS NOT NULL AND s.created_at >= ?)   AS converted_30d,
            COUNT(*) FILTER (WHERE s.expired_at IS NULL AND s.expires_at > ?)                 AS live_count,
            COALESCE(SUM(s.message_count), 0)                                                 AS total_messages,
            COUNT(*) FILTER (WHERE s.message_count > 0)                                       AS engaged_count
        FROM control_plane.demo_session s
        """;

    /**
     * A date spine {@code LEFT JOIN}ed twice, so a day on which nothing happened is still a point
     * on the chart rather than a missing x value. Bounds come in as {@code timestamptz} parameters
     * (see the class javadoc's note on time) and each bucket is a half-open UTC day.
     */
    private static final String DAILY_SQL =
            """
        WITH spine AS (
            SELECT generate_series(?::timestamptz, ?::timestamptz, interval '1 day') AS bucket
        )
        SELECT
            spine.bucket AS bucket,
            (SELECT COUNT(*) FROM control_plane.demo_session s
              WHERE s.created_at >= spine.bucket AND s.created_at < spine.bucket + interval '1 day') AS created,
            (SELECT COUNT(*) FROM control_plane.demo_session s
              WHERE s.converted_at >= spine.bucket AND s.converted_at < spine.bucket + interval '1 day') AS converted
        FROM spine
        ORDER BY spine.bucket
        """;

    private static final String RECENT_SQL =
            """
        SELECT
            s.id, s.tenant_id, s.tenant_schema, t.name AS tenant_name, s.email,
            s.created_at, s.expires_at, s.expired_at,
            s.converted_tenant_id, s.converted_at, s.message_count
        FROM control_plane.demo_session s
        LEFT JOIN control_plane.tenant t ON t.id = s.tenant_id
        ORDER BY s.created_at DESC
        LIMIT ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public DemoSandboxMetricsJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One pass over the table for every scalar on the page. */
    public Map<String, Object> summary(
            OffsetDateTime todayStart,
            OffsetDateTime sevenStart,
            OffsetDateTime thirtyStart,
            OffsetDateTime nowOffset) {
        return jdbcTemplate.queryForMap(
                SUMMARY_SQL,
                todayStart,
                sevenStart,
                thirtyStart,
                todayStart,
                sevenStart,
                thirtyStart,
                nowOffset);
    }

    public Map<String, Object> shareAndLabRow() {
        return jdbcTemplate.queryForMap(
                """
            SELECT COUNT(*) FILTER (WHERE s.share_id IS NOT NULL)      AS shares_created,
                   COALESCE(SUM(s.share_view_count), 0)                AS share_views,
                   COALESCE(SUM(s.share_viewer_count), 0)              AS share_viewers,
                   COUNT(*) FILTER (WHERE s.lab_started_at IS NOT NULL)   AS lab_started,
                   COUNT(*) FILTER (WHERE s.lab_completed_at IS NOT NULL) AS lab_completed
            FROM control_plane.demo_session s
            """);
    }

    public List<DemoDailyPoint> dailySeries(OffsetDateTime from, OffsetDateTime todayStart) {
        return jdbcTemplate.query(
                DAILY_SQL,
                (ResultSet rs, int rowNum) ->
                        new DemoDailyPoint(
                                rs.getObject("bucket", OffsetDateTime.class)
                                        .withOffsetSameInstant(ZoneOffset.UTC)
                                        .toLocalDate(),
                                rs.getLong("created"),
                                rs.getLong("converted")),
                from,
                todayStart);
    }

    public List<RecentRow> recent(int limit) {
        return jdbcTemplate.query(
                RECENT_SQL,
                (ResultSet rs, int rowNum) ->
                        new RecentRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getString("tenant_name"),
                                rs.getString("tenant_schema"),
                                rs.getString("email"),
                                instant(rs, "created_at"),
                                instant(rs, "expires_at"),
                                instant(rs, "expired_at"),
                                rs.getObject("converted_tenant_id", UUID.class),
                                instant(rs, "converted_at"),
                                rs.getInt("message_count")),
                limit);
    }

    public Map<String, Object> imageRow() {
        return jdbcTemplate.queryForMap(
                """
            SELECT COALESCE(SUM(s.image_count), 0)             AS images_generated,
                   COUNT(*) FILTER (WHERE s.image_count > 0)   AS sandboxes_with_images
            FROM control_plane.demo_session s
            """);
    }

    public Map<String, Object> channelRow() {
        return jdbcTemplate.queryForMap(
                """
            SELECT COUNT(*)                                        AS links,
                   COUNT(*) FILTER (WHERE l.state = 'LINKED')      AS linked,
                   COUNT(DISTINCT l.demo_session_id)               AS sandboxes_with_link
            FROM control_plane.demo_channel_link l
            """);
    }

    public boolean relationExists(String qualifiedName) {
        Boolean present =
                jdbcTemplate.queryForObject(
                        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, qualifiedName);
        return Boolean.TRUE.equals(present);
    }

    public boolean columnExists(String schema, String table, String column) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns "
                                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                        Integer.class,
                        schema,
                        table,
                        column);
        return count != null && count > 0;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
