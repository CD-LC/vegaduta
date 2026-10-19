package com.agenticai.core.platform;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/platform/demo-metrics} - the whole demo funnel in one read.
 *
 * @param available false when {@code control_plane.demo_session} does not exist
 *     (a deployment whose Flyway has not reached V126). Everything else is then
 *     null/empty and the console says "not available" rather than rendering a
 *     dashboard of zeros that looks like "nobody is using it".
 * @param generatedAt server clock at query time - every window below is
 *     measured backwards from this instant, in UTC.
 * @param liveCount sandboxes not yet retired and not yet past their expiry.
 * @param expiredCount the complement: retired by the expiry job, or simply past
 *     {@code expires_at} and awaiting the next job tick.
 * @param dailySeries oldest-first, one entry per UTC day, gaps filled with
 *     zeros.
 * @param sharesCreated sandboxes that minted a share link (V131's {@code
 *     share_id IS NOT NULL}); null - like the four fields after it - when the
 *     deployment's Flyway predates V131, same null-not-zero honesty the
 *     engagement block documents.
 * @param shareViews public GETs of share metadata, summed across shares.
 * @param shareViewers distinct viewer IPs actually admitted to a share, summed
 *     across shares (the durable counter, not the live Redis sets).
 * @param labStarted sessions that fired the {@code lab_started} funnel event.
 * @param labCompleted sessions that fired the {@code lab_completed} event.
 */
public record DemoMetricsResponse(
    boolean available,
    Instant generatedAt,
    DemoMetricsWindow today,
    DemoMetricsWindow last7Days,
    DemoMetricsWindow last30Days,
    DemoMetricsWindow allTime,
    long liveCount,
    long expiredCount,
    DemoEngagementMetrics engagement,
    List<DemoDailyPoint> dailySeries,
    List<DemoRecentSandbox> recent,
    Long sharesCreated,
    Long shareViews,
    Long shareViewers,
    Long labStarted,
    Long labCompleted
) {

    /** The honest empty payload for a database that predates V126. */
    static DemoMetricsResponse unavailable(Instant generatedAt) {
        return new DemoMetricsResponse(
            false, generatedAt, null, null, null, null, 0L, 0L, null, List.of(), List.of(),
            null, null, null, null, null
        );
    }
}
