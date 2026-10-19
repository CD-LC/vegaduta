package com.agenticai.core.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Demo-sandbox funnel analytics for the platform-admin console -
 * {@code GET /api/platform/demo-metrics}.
 *
 * <h2>Everything here is computed, nothing is stored</h2>
 * There is no rollup table and no counter to drift out of sync: every figure is
 * a live aggregate over {@code control_plane.demo_session} (V126), which
 * already records creation, expiry, message count and - the one that matters -
 * {@code converted_tenant_id}/{@code converted_at}. Raw {@link JdbcTemplate}
 * rather than JPA for the same reason {@code NewsletterService} does it: these
 * are cross-tenant control-plane reads over columns owned by other
 * workstreams, and a raw column read does not couple this feature's
 * compile-time correctness to whether some other module ever maps them.
 *
 * <h2>Degrading honestly</h2>
 * Two of the inputs land from separate workstreams -
 * {@code demo_session.image_count} (V127) and {@code
 * control_plane.demo_channel_link} (V128). Both are probed in the catalog at
 * query time; when absent the corresponding figures come back {@code null} with
 * a human-readable reason, never 0. Same for the base table itself: a
 * deployment whose Flyway has not reached V126 gets {@link
 * DemoMetricsResponse#unavailable}, not a dashboard of zeros. A zero and an
 * unknown look identical on a stat tile and mean opposite things, which is
 * exactly the kind of number an operator would make a decision on.
 *
 * <h2>Time</h2>
 * Every window and every daily bucket is UTC, anchored to the server clock at
 * request time and passed to Postgres as an explicit {@code timestamptz}
 * parameter - not computed inside SQL with {@code now()}/{@code date_trunc},
 * which would silently follow the database session's time zone and quietly
 * change what "today" means between environments.
 */
@Service
public class DemoSandboxMetricsService {

    private static final Logger log = LoggerFactory.getLogger(DemoSandboxMetricsService.class);

    private static final String DEMO_SESSION_TABLE = "control_plane.demo_session";
    private static final String CHANNEL_LINK_TABLE = "control_plane.demo_channel_link";

    /**
     * One pass over the table for every scalar on the page.
     *
     * <p>The conversion counters are COHORT counters - {@code created_at >= ?}
     * paired with {@code converted_tenant_id IS NOT NULL} - so the rate's
     * numerator and denominator describe the same set of sandboxes. See
     * {@link DemoMetricsWindow}'s javadoc for why that is the only definition
     * worth putting in front of an owner.
     *
     * <p>{@code converted_tenant_id IS NOT NULL} rather than {@code converted_at
     * IS NOT NULL}: V126 writes both together, but the tenant id is the column
     * that carries the actual conversion evidence (which real tenant this
     * sandbox became), so it is the one to test.
     */
    private static final String SUMMARY_SQL = """
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
     * A date spine {@code LEFT JOIN}ed twice, so a day on which nothing
     * happened is still a point on the chart rather than a missing x value.
     * Bounds come in as {@code timestamptz} parameters (see the class javadoc's
     * note on time) and each bucket is a half-open UTC day.
     */
    private static final String DAILY_SQL = """
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

    private static final String RECENT_SQL = """
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

    public DemoSandboxMetricsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param days length of the daily series, clamped to 1..90 by the caller.
     * @param recentLimit rows in the operator table, clamped to 1..200.
     */
    public DemoMetricsResponse metrics(int days, int recentLimit) {
        Instant now = Instant.now();
        if (!relationExists(DEMO_SESSION_TABLE)) {
            log.warn("{} does not exist - demo metrics unavailable (Flyway has not reached V126?)", DEMO_SESSION_TABLE);
            return DemoMetricsResponse.unavailable(now);
        }

        OffsetDateTime todayStart = startOfUtcDay(now);
        OffsetDateTime sevenStart = todayStart.minusDays(6);   // today + the 6 before it
        OffsetDateTime thirtyStart = todayStart.minusDays(29);
        OffsetDateTime nowOffset = now.atOffset(ZoneOffset.UTC);

        Map<String, Object> summary = jdbcTemplate.queryForMap(
            SUMMARY_SQL,
            todayStart, sevenStart, thirtyStart,
            todayStart, sevenStart, thirtyStart,
            nowOffset
        );

        long createdAll = asLong(summary.get("created_all"));
        long liveCount = asLong(summary.get("live_count"));
        long engaged = asLong(summary.get("engaged_count"));
        long totalMessages = asLong(summary.get("total_messages"));

        DemoEngagementMetrics engagement = DemoEngagementMetrics.ofMessages(
            totalMessages,
            createdAll > 0 ? ((double) totalMessages) / createdAll : null,
            engaged,
            createdAll - engaged,
            createdAll > 0 ? ((double) engaged) / createdAll : null
        );
        engagement = withImageMetrics(engagement);
        engagement = withChannelMetrics(engagement);

        ShareAndLabMetrics shareAndLab = shareAndLabMetrics();

        return new DemoMetricsResponse(
            true,
            now,
            DemoMetricsWindow.of("today", asLong(summary.get("created_today")), asLong(summary.get("converted_today"))),
            DemoMetricsWindow.of("last7Days", asLong(summary.get("created_7d")), asLong(summary.get("converted_7d"))),
            DemoMetricsWindow.of("last30Days", asLong(summary.get("created_30d")), asLong(summary.get("converted_30d"))),
            DemoMetricsWindow.of("allTime", createdAll, asLong(summary.get("converted_all"))),
            liveCount,
            createdAll - liveCount,
            engagement,
            dailySeries(todayStart, days),
            recent(recentLimit, now),
            shareAndLab.sharesCreated(),
            shareAndLab.shareViews(),
            shareAndLab.shareViewers(),
            shareAndLab.labStarted(),
            shareAndLab.labCompleted()
        );
    }

    private record ShareAndLabMetrics(
        Long sharesCreated, Long shareViews, Long shareViewers, Long labStarted, Long labCompleted
    ) {
        static ShareAndLabMetrics unavailable() {
            return new ShareAndLabMetrics(null, null, null, null, null);
        }
    }

    /**
     * Share-link + Academy-lab funnel figures, if the V131 workstream's
     * columns have landed - probed exactly like the V127/V128 inputs above,
     * and for the same reason: null-with-a-log, never a dashboard of zeros
     * that means the opposite of what it shows.
     */
    private ShareAndLabMetrics shareAndLabMetrics() {
        boolean usable = columnExists("control_plane", "demo_session", "share_id")
            && columnExists("control_plane", "demo_session", "share_view_count")
            && columnExists("control_plane", "demo_session", "share_viewer_count")
            && columnExists("control_plane", "demo_session", "lab_started_at")
            && columnExists("control_plane", "demo_session", "lab_completed_at");
        if (!usable) {
            log.debug("Share/lab funnel columns (migration V131) absent - reporting those figures as unavailable");
            return ShareAndLabMetrics.unavailable();
        }
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT COUNT(*) FILTER (WHERE s.share_id IS NOT NULL)      AS shares_created,
                   COALESCE(SUM(s.share_view_count), 0)                AS share_views,
                   COALESCE(SUM(s.share_viewer_count), 0)              AS share_viewers,
                   COUNT(*) FILTER (WHERE s.lab_started_at IS NOT NULL)   AS lab_started,
                   COUNT(*) FILTER (WHERE s.lab_completed_at IS NOT NULL) AS lab_completed
            FROM control_plane.demo_session s
            """);
        return new ShareAndLabMetrics(
            asLong(row.get("shares_created")),
            asLong(row.get("share_views")),
            asLong(row.get("share_viewers")),
            asLong(row.get("lab_started")),
            asLong(row.get("lab_completed"))
        );
    }

    // ---------------------------------------------------------------------

    private List<DemoDailyPoint> dailySeries(OffsetDateTime todayStart, int days) {
        OffsetDateTime from = todayStart.minusDays(days - 1L);
        return jdbcTemplate.query(DAILY_SQL, (ResultSet rs, int rowNum) -> new DemoDailyPoint(
            rs.getObject("bucket", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
            rs.getLong("created"),
            rs.getLong("converted")
        ), from, todayStart);
    }

    private List<DemoRecentSandbox> recent(int limit, Instant now) {
        return jdbcTemplate.query(RECENT_SQL, (ResultSet rs, int rowNum) -> {
            Instant expiresAt = instant(rs, "expires_at");
            Instant expiredAt = instant(rs, "expired_at");
            Instant convertedAt = instant(rs, "converted_at");
            UUID convertedTenantId = rs.getObject("converted_tenant_id", UUID.class);
            return new DemoRecentSandbox(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_name"),
                rs.getString("tenant_schema"),
                rs.getString("email"),
                instant(rs, "created_at"),
                expiresAt,
                expiredAt,
                convertedTenantId,
                convertedAt,
                rs.getInt("message_count"),
                status(convertedTenantId, expiredAt, expiresAt, now)
            );
        }, limit);
    }

    /** Derived, never stored - see {@link DemoRecentSandbox#status()}. */
    private static String status(UUID convertedTenantId, Instant expiredAt, Instant expiresAt, Instant now) {
        if (convertedTenantId != null) {
            return "CONVERTED";
        }
        if (expiredAt != null || (expiresAt != null && !expiresAt.isAfter(now))) {
            return "EXPIRED";
        }
        return "LIVE";
    }

    /**
     * Image usage, if the V127 workstream's column has landed. {@code
     * image_count} is a denormalised counter on the session row, so this is one
     * more aggregate over a table already in cache rather than a join against
     * {@code demo_generated_image}.
     */
    private DemoEngagementMetrics withImageMetrics(DemoEngagementMetrics base) {
        if (!columnExists("control_plane", "demo_session", "image_count")) {
            return base.withImages(null, null,
                "Image usage is not tracked in this deployment - control_plane.demo_session.image_count "
                    + "(migration V127) has not been applied.");
        }
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT COALESCE(SUM(s.image_count), 0)             AS images_generated,
                   COUNT(*) FILTER (WHERE s.image_count > 0)   AS sandboxes_with_images
            FROM control_plane.demo_session s
            """);
        return base.withImages(asLong(row.get("images_generated")), asLong(row.get("sandboxes_with_images")), null);
    }

    /**
     * Channel-link counts, if the V128 workstream's table has landed. Both the
     * table AND the two columns this reads are probed - a table that exists
     * with a different shape than expected must degrade to "unavailable", not
     * blow up the whole metrics call with a SQL error.
     */
    private DemoEngagementMetrics withChannelMetrics(DemoEngagementMetrics base) {
        boolean usable = relationExists(CHANNEL_LINK_TABLE)
            && columnExists("control_plane", "demo_channel_link", "demo_session_id")
            && columnExists("control_plane", "demo_channel_link", "state");
        if (!usable) {
            return base.withChannels(null, null, null,
                "Channel linking is not tracked in this deployment - control_plane.demo_channel_link "
                    + "(migration V128) is absent or has an unexpected shape.");
        }
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT COUNT(*)                                        AS links,
                   COUNT(*) FILTER (WHERE l.state = 'LINKED')      AS linked,
                   COUNT(DISTINCT l.demo_session_id)               AS sandboxes_with_link
            FROM control_plane.demo_channel_link l
            """);
        return base.withChannels(
            asLong(row.get("links")), asLong(row.get("linked")), asLong(row.get("sandboxes_with_link")), null
        );
    }

    // --- catalog probes ---------------------------------------------------

    private boolean relationExists(String qualifiedName) {
        Boolean present = jdbcTemplate.queryForObject(
            "SELECT to_regclass(?) IS NOT NULL", Boolean.class, qualifiedName
        );
        return Boolean.TRUE.equals(present);
    }

    private boolean columnExists(String schema, String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
            Integer.class, schema, table, column
        );
        return count != null && count > 0;
    }

    // --- small helpers ----------------------------------------------------

    /** Midnight UTC of the day {@code instant} falls in. */
    private static OffsetDateTime startOfUtcDay(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * {@code COUNT} comes back as {@code Long} and {@code SUM(integer)} as
     * {@code Long} too, but {@code COALESCE(SUM(...), 0)} can widen to {@code
     * BigDecimal} depending on the driver's type inference - one helper rather
     * than a cast that works until it doesn't.
     */
    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
