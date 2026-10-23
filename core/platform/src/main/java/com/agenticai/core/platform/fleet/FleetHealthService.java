package com.agenticai.core.platform.fleet;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fleet health: the per-agent SLO picture a platform operator needs across every tenant -
 * "PagerDuty for agents", not chat history. One read-only call walks every ACTIVE non-demo tenant
 * schema (same {@code TenantContext} binding discipline {@code
 * PlatformTenantDetailController#asTenant} documents) and aggregates three durable signals per
 * agent:
 *
 * <ul>
 *   <li>{@code llm_call_log} - call volume, error rate, spend in the window plus a 7-day daily-cost
 *       baseline for spend-spike detection.
 *   <li>{@code agent_job} - finished-job success rate in the window.
 *   <li>{@code eval_run} - the agent's two newest SUCCEEDED runs; the delta between latest and
 *       previous {@code overall_score} is the drift signal (eval-score regression over time).
 * </ul>
 *
 * <p>Alert derivation is honest-absence: an agent with no traffic gets no error-rate verdict (a 0/0
 * division is not "100% healthy"), an agent with one eval run gets no drift verdict (nothing to
 * regress FROM), and a tenant whose schema cannot be read is listed in {@code unavailableTenants}
 * rather than silently contributing zeros. Kill-switch escalation is deliberately NOT
 * re-implemented here - the response carries {@code emergencyStopped} and the console acts through
 * the existing {@code /api/platform/tenants/{id}/emergency-stop|resume} endpoints.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class FleetHealthService {

    private static final Logger log = LoggerFactory.getLogger(FleetHealthService.class);
    private static final String SYSTEM_ACTOR = "system:fleet-health";

    /**
     * Alert tokens emitted in {@link AgentHealth#alerts} - stable machine values for the console.
     */
    public static final String ALERT_EMERGENCY_STOPPED = "EMERGENCY_STOPPED";

    public static final String ALERT_ERROR_RATE_BREACH = "ERROR_RATE_BREACH";
    public static final String ALERT_JOB_SUCCESS_BREACH = "JOB_SUCCESS_BREACH";
    public static final String ALERT_EVAL_FAILED = "EVAL_FAILED";
    public static final String ALERT_EVAL_REGRESSION = "EVAL_REGRESSION";
    public static final String ALERT_SPEND_SPIKE = "SPEND_SPIKE";

    /**
     * Minimum finished jobs in the window before a job-success rate is judged (0/0 is not a
     * breach).
     */
    static final long MIN_JOBS_FOR_SLO = 3;

    /** Baseline window for the spend-spike comparison. */
    static final int BASELINE_DAYS = 7;

    @PersistenceContext private EntityManager entityManager;

    private final TenantRepository tenantRepository;

    public FleetHealthService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /** Tunables the operator can override per request; see the controller for defaults. */
    public record FleetSlo(
            int windowHours,
            double maxErrorRate,
            double minJobSuccessRate,
            double evalDriftDrop,
            double spendSpikeRatio,
            long minCallsForSlo,
            double minSpendUsd) {
        public static FleetSlo defaults(int windowHours) {
            return new FleetSlo(windowHours, 0.10, 0.90, 0.05, 3.0, 5, 1.0);
        }
    }

    public record AgentHealth(
            UUID tenantId,
            String tenantName,
            boolean tenantEmergencyStopped,
            UUID agentId,
            String agentName,
            long calls,
            long failedCalls,
            Double errorRate,
            BigDecimal costUsd,
            long tokens,
            BigDecimal baselineDailyCostUsd,
            Double spendRatio,
            long jobsFinished,
            long jobsSucceeded,
            Double jobSuccessRate,
            Double latestEvalScore,
            Double previousEvalScore,
            String latestEvalVerdict,
            Instant latestEvalFinishedAt,
            Instant lastCallAt,
            List<String> alerts,
            String health) {}

    public record FleetHealthResponse(
            Instant generatedAt,
            FleetSlo slo,
            List<AgentHealth> agents,
            List<String> unavailableTenants) {}

    /**
     * Aggregates the whole fleet. Read-only; never throws on a single bad tenant schema - the
     * tenant is recorded in {@code unavailableTenants} and the rest of the fleet still reports.
     */
    public FleetHealthResponse health(FleetSlo slo) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofHours(slo.windowHours()));
        Instant baselineStart = now.minus(Duration.ofDays(BASELINE_DAYS));

        List<AgentHealth> agents = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (Tenant tenant : tenantRepository.findActiveExcludingDemo()) {
            String schema = tenant.getSchemaName();
            try {
                agents.addAll(
                        asTenant(
                                schema,
                                () -> agentHealth(tenant, windowStart, baselineStart, slo)));
            } catch (RuntimeException e) {
                unavailable.add(tenant.getName() != null ? tenant.getName() : schema);
                log.warn(
                        "Fleet health: schema '{}' unreadable - tenant reported as unavailable",
                        schema,
                        e);
            }
        }
        agents.sort(
                Comparator.comparingInt((AgentHealth a) -> severity(a.health()))
                        .reversed()
                        .thenComparing(
                                (AgentHealth a) ->
                                        -Optional.ofNullable(a.errorRate()).orElse(0.0)));
        return new FleetHealthResponse(now, slo, List.copyOf(agents), List.copyOf(unavailable));
    }

    // ------------------------------------------------------------------ per-tenant aggregation

    /** All queries below run inside the target tenant's schema binding. */
    private List<AgentHealth> agentHealth(
            Tenant tenant, Instant windowStart, Instant baselineStart, FleetSlo slo) {
        Map<UUID, String> agentNames = agentNames();
        Map<UUID, LlmRow> llm = llmStats(windowStart, baselineStart);
        Map<UUID, JobRow> jobs = jobStats(windowStart, baselineStart);
        Map<UUID, List<EvalRow>> evals = evalDrift();

        List<AgentHealth> out = new ArrayList<>();
        for (Map.Entry<UUID, String> e : agentNames.entrySet()) {
            out.add(
                    assemble(
                            tenant,
                            e.getKey(),
                            e.getValue(),
                            llm.get(e.getKey()),
                            jobs.get(e.getKey()),
                            evals.get(e.getKey()),
                            slo));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, String> agentNames() {
        List<Object[]> rows =
                entityManager.createNativeQuery("SELECT id, name FROM agent").getResultList();
        Map<UUID, String> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put((UUID) r[0], (String) r[1]);
        }
        return out;
    }

    /**
     * Per-agent LLM traffic in-window plus the 7-day daily-cost baseline. {@code agent_id IS NULL}
     * rows (tenant-level calls not bound to an agent) are excluded - they cannot be attributed to
     * an SLO.
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, LlmRow> llmStats(Instant windowStart, Instant baselineStart) {
        List<Object[]> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT agent_id, "
                                        + "COUNT(*) FILTER (WHERE started_at >= :windowStart), "
                                        + "COUNT(*) FILTER (WHERE started_at >= :windowStart AND NOT success), "
                                        + "COALESCE(SUM(estimated_cost_usd) FILTER (WHERE started_at >= :windowStart), 0), "
                                        + "COALESCE(SUM(total_tokens) FILTER (WHERE started_at >= :windowStart), 0), "
                                        + "COALESCE(SUM(estimated_cost_usd), 0) / "
                                        + BASELINE_DAYS
                                        + ".0, "
                                        + "MAX(started_at) "
                                        + "FROM llm_call_log "
                                        + "WHERE started_at >= :baselineStart AND agent_id IS NOT NULL "
                                        + "GROUP BY agent_id")
                        .setParameter("windowStart", Timestamp.from(windowStart))
                        .setParameter("baselineStart", Timestamp.from(baselineStart))
                        .getResultList();
        Map<UUID, LlmRow> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put(
                    (UUID) r[0],
                    new LlmRow(
                            ((Number) r[1]).longValue(),
                            ((Number) r[2]).longValue(),
                            (BigDecimal) r[3],
                            ((Number) r[4]).longValue(),
                            (BigDecimal) r[5],
                            toInstant(r[6])));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, JobRow> jobStats(Instant windowStart, Instant baselineStart) {
        List<Object[]> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT agent_id, "
                                        + "COUNT(*) FILTER (WHERE finished_at >= :windowStart), "
                                        + "COUNT(*) FILTER (WHERE finished_at >= :windowStart AND status = 'SUCCEEDED'), "
                                        + "COUNT(*) FILTER (WHERE finished_at >= :windowStart AND status = 'FAILED') "
                                        + "FROM agent_job "
                                        + "WHERE finished_at >= :baselineStart "
                                        + "GROUP BY agent_id")
                        .setParameter("windowStart", Timestamp.from(windowStart))
                        .setParameter("baselineStart", Timestamp.from(baselineStart))
                        .getResultList();
        Map<UUID, JobRow> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put(
                    (UUID) r[0],
                    new JobRow(
                            ((Number) r[1]).longValue(),
                            ((Number) r[2]).longValue(),
                            ((Number) r[3]).longValue()));
        }
        return out;
    }

    /**
     * Each agent's two newest SUCCEEDED eval runs (any age - evals are sparse; restricting them to
     * the traffic window would report "no drift" simply because nobody ran one lately). Drift is
     * latest-minus-previous; a single run yields no verdict.
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, List<EvalRow>> evalDrift() {
        List<Object[]> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT agent_id, verdict, overall_score, finished_at FROM ("
                                        + "  SELECT agent_id, verdict, overall_score, finished_at, "
                                        + "    ROW_NUMBER() OVER (PARTITION BY agent_id "
                                        + "      ORDER BY finished_at DESC NULLS LAST, created_at DESC) AS rn "
                                        + "  FROM eval_run WHERE status = 'SUCCEEDED'"
                                        + ") t WHERE rn <= 2")
                        .getResultList();
        Map<UUID, List<EvalRow>> out = new HashMap<>();
        for (Object[] r : rows) {
            Double score =
                    r[2] instanceof BigDecimal bd
                            ? bd.doubleValue()
                            : r[2] instanceof Number n ? n.doubleValue() : null;
            out.computeIfAbsent((UUID) r[0], k -> new ArrayList<>())
                    .add(new EvalRow((String) r[1], score, toInstant(r[3])));
        }
        return out;
    }

    // ------------------------------------------------------------------ alert derivation

    private AgentHealth assemble(
            Tenant tenant,
            UUID agentId,
            String agentName,
            LlmRow llm,
            JobRow jobs,
            List<EvalRow> evals,
            FleetSlo slo) {
        long calls = llm != null ? llm.calls() : 0;
        long failed = llm != null ? llm.failedCalls() : 0;
        Double errorRate = calls > 0 ? (double) failed / calls : null;
        BigDecimal cost = llm != null ? llm.costUsd() : BigDecimal.ZERO;
        long tokens = llm != null ? llm.tokens() : 0;
        BigDecimal baselineDaily = llm != null ? llm.baselineDailyCostUsd() : BigDecimal.ZERO;
        Double spendRatio =
                baselineDaily.signum() > 0
                        ? cost.doubleValue() / baselineDaily.doubleValue()
                        : null;

        long jobsFinished = jobs != null ? jobs.finished() : 0;
        long jobsSucceeded = jobs != null ? jobs.succeeded() : 0;
        Double jobSuccessRate = jobsFinished > 0 ? (double) jobsSucceeded / jobsFinished : null;

        EvalRow latest = evals != null && !evals.isEmpty() ? evals.get(0) : null;
        EvalRow previous = evals != null && evals.size() > 1 ? evals.get(1) : null;

        Set<String> alerts = new LinkedHashSet<>();
        if (tenant.isEmergencyStopped()) {
            alerts.add(ALERT_EMERGENCY_STOPPED);
        }
        if (errorRate != null && calls >= slo.minCallsForSlo() && errorRate > slo.maxErrorRate()) {
            alerts.add(ALERT_ERROR_RATE_BREACH);
        }
        if (jobSuccessRate != null
                && jobsFinished >= MIN_JOBS_FOR_SLO
                && jobSuccessRate < slo.minJobSuccessRate()) {
            alerts.add(ALERT_JOB_SUCCESS_BREACH);
        }
        if (latest != null) {
            if ("FAILED".equals(latest.verdict())) {
                alerts.add(ALERT_EVAL_FAILED);
            }
            if (latest.score() != null
                    && previous != null
                    && previous.score() != null
                    && previous.score() - latest.score() > slo.evalDriftDrop()) {
                alerts.add(ALERT_EVAL_REGRESSION);
            }
        }
        if (spendRatio != null
                && spendRatio > slo.spendSpikeRatio()
                && cost.doubleValue() >= slo.minSpendUsd()) {
            alerts.add(ALERT_SPEND_SPIKE);
        }

        return new AgentHealth(
                tenant.getId(),
                tenant.getName(),
                tenant.isEmergencyStopped(),
                agentId,
                agentName,
                calls,
                failed,
                errorRate,
                cost,
                tokens,
                baselineDaily,
                spendRatio,
                jobsFinished,
                jobsSucceeded,
                jobSuccessRate,
                latest != null ? latest.score() : null,
                previous != null ? previous.score() : null,
                latest != null ? latest.verdict() : null,
                latest != null ? latest.finishedAt() : null,
                llm != null ? llm.lastCallAt() : null,
                List.copyOf(alerts),
                healthOf(tenant, alerts));
    }

    private static String healthOf(Tenant tenant, Set<String> alerts) {
        if (tenant.isEmergencyStopped()) {
            return "STOPPED";
        }
        if (alerts.contains(ALERT_ERROR_RATE_BREACH)
                || alerts.contains(ALERT_JOB_SUCCESS_BREACH)
                || alerts.contains(ALERT_EVAL_FAILED)) {
            return "BREACH";
        }
        return alerts.isEmpty() ? "OK" : "WARN";
    }

    private static int severity(String health) {
        return switch (health) {
            case "STOPPED" -> 3;
            case "BREACH" -> 2;
            case "WARN" -> 1;
            default -> 0;
        };
    }

    /** Binds the TARGET tenant schema for the call and restores the caller's context afterwards. */
    private <T> T asTenant(String schema, Supplier<T> work) {
        Optional<TenantContext> previous = TenantContext.current();
        try {
            TenantContext.set(TenantContext.of(schema, SYSTEM_ACTOR, Set.of()));
            return work.get();
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }
    }

    private static Instant toInstant(Object o) {
        return o instanceof Timestamp ts ? ts.toInstant() : o instanceof Instant i ? i : null;
    }

    private record LlmRow(
            long calls,
            long failedCalls,
            BigDecimal costUsd,
            long tokens,
            BigDecimal baselineDailyCostUsd,
            Instant lastCallAt) {}

    private record JobRow(long finished, long succeeded, long failed) {}

    private record EvalRow(String verdict, Double score, Instant finishedAt) {}
}
