package com.agenticai.core.agents.experiment;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only {@code llm_call_log}/{@code turn_feedback} aggregation for
 * {@link AgentExperimentService}, pulled out into its own bean rather than
 * living inline in that service.
 *
 * <p>The split is not stylistic - it is what makes {@link
 * AgentExperimentService#sweepAutoStops} correct. That method loops over
 * every RUNNING experiment across every tenant schema and needs each
 * experiment's read to run in its OWN short transaction (the same reasoning
 * {@code EvalRunExecutor}'s boot sweep documents for why its own repository
 * call - not the sweep method - carries {@code @Transactional}). A {@code
 * @Transactional} method called via {@code this.} from inside the SAME class
 * is invisible to Spring's proxy-based AOP (the classic self-invocation
 * gap), so the query methods below could not simply be private helpers on
 * {@code AgentExperimentService} itself - they have to be genuine
 * cross-bean calls for the annotation to take effect.
 *
 * <p>Raw {@link EntityManager} native queries rather than typed repositories
 * on purpose: {@code llm_call_log} ({@code core/chat}) and {@code
 * turn_feedback} ({@code core/rl}) are both real Maven dependencies of
 * {@code core/agents} already (unlike {@code core/quality}'s module-boundary
 * story), but this is a two-table, two-agent-id, date-windowed GROUP-BY
 * report that has no natural home as a derived query method on either
 * module's own repository - see {@code EvalGateService}'s identical choice
 * for the same shape of read.
 */
@Component
public class ExperimentMetricsReader {

    @PersistenceContext
    private EntityManager entityManager;

    /** {count, avgLatencyMs, totalCostUsd, errorCount} for one agent's llm_call_log rows in the window. */
    @Transactional(readOnly = true)
    public Object[] queryLlmCallStats(UUID agentId, Instant windowStart, Instant windowEnd) {
        return queryOneRow(
            "SELECT COUNT(*), AVG(latency_ms), COALESCE(SUM(estimated_cost_usd), 0), "
                + "SUM(CASE WHEN success = false THEN 1 ELSE 0 END) "
                + "FROM llm_call_log WHERE agent_id = CAST(:agentId AS uuid) AND started_at >= :windowStart",
            agentId, windowStart, windowEnd, "started_at");
    }

    /** {upCount, upOrDownCount} for one agent's turn_feedback rows in the window. */
    @Transactional(readOnly = true)
    public Object[] queryFeedbackStats(UUID agentId, Instant windowStart, Instant windowEnd) {
        return queryOneRow(
            "SELECT SUM(CASE WHEN signal = 'UP' THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN signal IN ('UP','DOWN') THEN 1 ELSE 0 END) "
                + "FROM turn_feedback WHERE agent_id = CAST(:agentId AS uuid) AND created_at >= :windowStart",
            agentId, windowStart, windowEnd, "created_at");
    }

    /**
     * {sampleSize, errorCount} over one agent's most recent {@code lookback}
     * {@code llm_call_log} rows - the auto-stop guardrail's read. Not
     * time-windowed like the two methods above: a fixed sample size, not a
     * fixed time window, is what {@link AgentExperimentService#sweepAutoStops}
     * needs - see its javadoc.
     */
    @Transactional(readOnly = true)
    public Object[] queryRecentErrorSample(UUID agentId, int lookback) {
        Object result = entityManager.createNativeQuery(
                "SELECT COUNT(*), SUM(CASE WHEN success = false THEN 1 ELSE 0 END) FROM ("
                    + "SELECT success FROM llm_call_log WHERE agent_id = CAST(:agentId AS uuid) "
                    + "ORDER BY started_at DESC LIMIT :lookback) recent")
            .setParameter("agentId", agentId.toString())
            .setParameter("lookback", lookback)
            .getSingleResult();
        return asRow(result);
    }

    private Object[] queryOneRow(String baseSql, UUID agentId, Instant windowStart, Instant windowEnd, String timeColumn) {
        StringBuilder sql = new StringBuilder(baseSql);
        if (windowEnd != null) {
            sql.append(" AND ").append(timeColumn).append(" <= :windowEnd");
        }
        jakarta.persistence.Query query = entityManager.createNativeQuery(sql.toString())
            .setParameter("agentId", agentId.toString())
            .setParameter("windowStart", Timestamp.from(windowStart));
        if (windowEnd != null) {
            query.setParameter("windowEnd", Timestamp.from(windowEnd));
        }
        return asRow(query.getSingleResult());
    }

    private static Object[] asRow(Object result) {
        // A plain aggregate with no GROUP BY always returns exactly one row;
        // Hibernate hands back an Object[] for a multi-column projection.
        return result instanceof Object[] row ? row : new Object[] {result};
    }
}
