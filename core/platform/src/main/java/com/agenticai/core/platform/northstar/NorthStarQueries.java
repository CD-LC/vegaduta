package com.agenticai.core.platform.northstar;

import com.agenticai.core.platform.northstar.NorthStarAggregator.FirstRun;
import com.agenticai.core.platform.northstar.NorthStarAggregator.PassKRow;
import com.agenticai.core.platform.northstar.NorthStarAggregator.RunBucket;
import com.agenticai.core.platform.northstar.NorthStarAggregator.RunKind;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The thin SQL half of the north-star metrics. Every method runs ONE bounded,
 * grouped query and returns already-aggregated rows - never per-run rows - and
 * never selects a raw user id: users are reduced to {@code md5(id)} inside
 * Postgres and further prefixed with the tenant schema here. All the methods
 * that read tenant tables must be called with that tenant's {@code
 * TenantContext} bound ({@link NorthStarService} does this); {@link
 * #signupsByWeek} reads only {@code control_plane.*} and needs no binding.
 *
 * <p>Every query carries the JPA query-timeout hint, so one slow tenant costs
 * at most {@code app.platform.north-star.per-tenant-timeout-ms} per query and
 * is reported, not awaited. Windows are UTC ISO weeks ({@code date_trunc('week')}
 * starts on Monday).
 *
 * <p>Sources, all verified against the Flyway migrations:
 * {@code agent_job} (V207: origin, status, requested_by, created_at),
 * {@code quick_task_run} (V53: channel, status, requested_by_keycloak_user_id,
 * started_at), {@code eval_run} + {@code eval_run_case_result} (V213/V343:
 * repeats, scores_json with the {@code REPEATS} dimension written by
 * EvalRunService) and {@code control_plane.app_user} (created_at).
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Component
public class NorthStarQueries {

    /** JPA 3.1 standard hint; Hibernate 6 maps it to the JDBC statement timeout (milliseconds). */
    static final String TIMEOUT_HINT = "jakarta.persistence.query.timeout";

    private static final String WEEK = "to_char(date_trunc('week', %s AT TIME ZONE 'UTC'), 'YYYY-MM-DD')";

    @PersistenceContext
    private EntityManager entityManager;

    private final int timeoutMs;

    public NorthStarQueries(@Value("${app.platform.north-star.per-tenant-timeout-ms:5000}") int timeoutMs) {
        this.timeoutMs = Math.max(500, timeoutMs);
    }

    /** Terminal agent jobs (cancelled and still-live jobs are neither a success nor a failure). */
    public List<RunBucket> jobRuns(String tenantKey, Instant since) {
        List<Object[]> rows = rows(entityManager.createNativeQuery(
                "SELECT " + String.format(WEEK, "created_at") + ", origin, status, md5(requested_by), COUNT(*) "
                    + "FROM agent_job "
                    + "WHERE created_at >= :since AND status IN ('SUCCEEDED', 'FAILED') "
                    + "GROUP BY 1, 2, 3, 4")
            .setParameter("since", Timestamp.from(since)));
        List<RunBucket> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            RunKind kind = NorthStarAggregator.kindForJobOrigin((String) r[1]);
            if (kind == null) {
                continue; // an origin this code does not know: skipped, never guessed into a bucket
            }
            out.add(new RunBucket(LocalDate.parse((String) r[0]), kind, null,
                "SUCCEEDED".equals(r[2]), userKey(tenantKey, r[3]), ((Number) r[4]).longValue()));
        }
        return out;
    }

    /** Terminal Quick Task runs (RUNNING rows are in flight, so neither success nor failure). */
    public List<RunBucket> quickTaskRuns(String tenantKey, Instant since) {
        List<Object[]> rows = rows(entityManager.createNativeQuery(
                "SELECT " + String.format(WEEK, "started_at") + ", channel, status, "
                    + "md5(requested_by_keycloak_user_id), COUNT(*) "
                    + "FROM quick_task_run "
                    + "WHERE started_at >= :since AND status IN ('COMPLETED', 'FAILED') "
                    + "GROUP BY 1, 2, 3, 4")
            .setParameter("since", Timestamp.from(since)));
        List<RunBucket> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String channel = (String) r[1];
            RunKind kind = NorthStarAggregator.kindForQuickTaskChannel(channel);
            if (kind == null) {
                continue;
            }
            out.add(new RunBucket(LocalDate.parse((String) r[0]), kind,
                kind == RunKind.CHANNEL ? channel : null,
                "COMPLETED".equals(r[2]), userKey(tenantKey, r[3]), ((Number) r[4]).longValue()));
        }
        return out;
    }

    /**
     * For users of this tenant who signed up inside the window: minutes from
     * {@code app_user.created_at} to their first successful user-initiated run
     * (an API-origin job that SUCCEEDED, or a COMPLETED Quick Task; the run's
     * start time is used). Users with no such run are simply absent - the
     * signup count for the denominator comes from {@link #signupsByWeek}.
     */
    public List<FirstRun> firstRuns(String tenantKey, UUID tenantId, Instant since) {
        List<Object[]> rows = rows(entityManager.createNativeQuery(
                "WITH new_users AS ("
                    + "  SELECT keycloak_user_id AS uid, created_at FROM control_plane.app_user "
                    + "  WHERE tenant_id = :tenantId AND created_at >= :since"
                    + "), firsts AS ("
                    + "  SELECT j.requested_by AS uid, MIN(j.created_at) AS first_at FROM agent_job j "
                    + "  WHERE j.status = 'SUCCEEDED' AND j.origin = 'API' "
                    + "    AND j.requested_by IN (SELECT uid FROM new_users) GROUP BY j.requested_by "
                    + "  UNION ALL "
                    + "  SELECT q.requested_by_keycloak_user_id AS uid, MIN(q.started_at) AS first_at "
                    + "  FROM quick_task_run q "
                    + "  WHERE q.status = 'COMPLETED' "
                    + "    AND q.requested_by_keycloak_user_id IN (SELECT uid FROM new_users) "
                    + "  GROUP BY q.requested_by_keycloak_user_id"
                    + ") "
                    + "SELECT " + String.format(WEEK, "n.created_at") + ", md5(n.uid), "
                    + "  EXTRACT(EPOCH FROM (MIN(f.first_at) - n.created_at)) / 60.0 "
                    + "FROM new_users n JOIN firsts f ON f.uid = n.uid "
                    + "GROUP BY n.uid, n.created_at")
            .setParameter("tenantId", tenantId)
            .setParameter("since", Timestamp.from(since)));
        List<FirstRun> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new FirstRun(LocalDate.parse((String) r[0]), userKey(tenantKey, r[1]),
                ((Number) r[2]).doubleValue()));
        }
        return out;
    }

    /**
     * Eval cases replayed {@code repeats >= 2} times, grouped per requesting
     * user and k, with how many cases had ALL repetitions pass. EvalRunService
     * stores {@code REPEATS.score = passVotes / repetitions} per case, so
     * {@code score >= 0.9999} means every one of the k repetitions passed. The
     * {@code CASE} guards the jsonb cast so only rows that carry the REPEATS
     * dimension are ever parsed.
     */
    public List<PassKRow> passK(String tenantKey, Instant since) {
        List<Object[]> rows = rows(entityManager.createNativeQuery(
                "SELECT u, k, COUNT(*), COUNT(*) FILTER (WHERE all_passed) FROM ("
                    + "  SELECT md5(r.requested_by) AS u, r.repeats AS k, "
                    + "    CASE WHEN c.scores_json LIKE '%\"REPEATS\"%' "
                    + "      THEN COALESCE(CAST(CAST(c.scores_json AS jsonb) -> 'REPEATS' ->> 'score' AS numeric) >= 0.9999, FALSE) "
                    + "    END AS all_passed "
                    + "  FROM eval_run_case_result c JOIN eval_run r ON r.id = c.run_id "
                    + "  WHERE r.repeats > 1 AND r.created_at >= :since "
                    + "    AND r.status = 'SUCCEEDED' AND c.status = 'SCORED'"
                    + ") t WHERE all_passed IS NOT NULL GROUP BY u, k")
            .setParameter("since", Timestamp.from(since)));
        List<PassKRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new PassKRow(userKey(tenantKey, r[0]), ((Number) r[1]).intValue(),
                ((Number) r[2]).longValue(), ((Number) r[3]).longValue()));
        }
        return out;
    }

    /**
     * Users who signed up per ISO week in ACTIVE, non-demo tenants. Reads only
     * {@code control_plane.*} so it runs without a tenant binding.
     */
    public Map<LocalDate, Long> signupsByWeek(Instant since) {
        List<Object[]> rows = rows(entityManager.createNativeQuery(
                "SELECT " + String.format(WEEK, "a.created_at") + ", COUNT(*) "
                    + "FROM control_plane.app_user a JOIN control_plane.tenant t ON t.id = a.tenant_id "
                    + "WHERE a.created_at >= :since AND t.status = 'ACTIVE' AND NOT t.is_demo "
                    + "GROUP BY 1")
            .setParameter("since", Timestamp.from(since)));
        Map<LocalDate, Long> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put(LocalDate.parse((String) r[0]), ((Number) r[1]).longValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(Query query) {
        return query.setHint(TIMEOUT_HINT, timeoutMs).getResultList();
    }

    /** {@code schema:md5} - distinct per tenant, and never the raw user id. */
    private static String userKey(String tenantKey, Object md5) {
        return tenantKey + ":" + md5;
    }
}
