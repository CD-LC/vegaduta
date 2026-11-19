package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.PilotDtos.BaselineResponse;
import com.agenticai.core.common.roi.RoiDtos.AssumptionResponse;
import com.agenticai.core.common.roi.RoiDtos.PriceResponse;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Raw SQL for the Cost &amp; ROI services ({@link RoiPriceService}, {@link RoiBudgetService},
 * {@link RoiBaselineService}, {@link RoiOutcomeService}, {@link RoiAgentService}, {@link
 * RoiCostService}, {@link PilotReportService}).
 *
 * <p>No transaction handling here on purpose: a raw {@code JdbcTemplate} only runs in the tenant's
 * schema when it joins the JPA-bound connection that {@code SchemaMultiTenantConnectionProvider}
 * scopes, which needs the open transaction the calling service owns.
 */
@Repository
public class RoiJdbcRepository {

    private static final String OUTCOMES_SQL =
            """
        WITH latest AS (
          SELECT DISTINCT ON (conversation_key) conversation_key, agent_id, outcome, evidence
          FROM resolution_event WHERE resolved_at >= ? AND resolved_at < ?
          ORDER BY conversation_key, resolved_at DESC, created_at DESC
        ), conv AS (
          SELECT l.*, cs.session_id AS cs_session
          FROM latest l LEFT JOIN channel_session cs ON l.conversation_key = 'cs:' || cs.id::text
        )
        SELECT agent_id,
          count(*) AS conversations,
          count(*) FILTER (WHERE outcome = 'RESOLVED_AI' AND evidence <> 'NO_ESCALATION_IDLE') AS confirmed,
          count(*) FILTER (WHERE outcome = 'RESOLVED_AI' AND evidence = 'NO_ESCALATION_IDLE') AS assumed,
          count(*) FILTER (WHERE outcome = 'RESOLVED_HUMAN') AS by_human,
          count(*) FILTER (WHERE outcome = 'ESCALATED') AS handed,
          count(*) FILTER (WHERE outcome = 'ABANDONED') AS abandoned,
          count(*) FILTER (WHERE outcome = 'UNRESOLVED') AS unresolved,
          count(*) FILTER (WHERE EXISTS (SELECT 1 FROM conversation_correction cc WHERE cc.conversation_key = conv.conversation_key)
                              OR EXISTS (SELECT 1 FROM turn_feedback tf WHERE tf.signal = 'EDIT'
                                         AND tf.session_id IN (conv.conversation_key, COALESCE(conv.cs_session, conv.conversation_key)))) AS corrected
        FROM conv GROUP BY agent_id
        """;

    private static final String APPROVALS_SQL =
            """
        SELECT substring(requested_by FROM '^agent:([0-9a-fA-F-]{36}):')::uuid AS agent_id,
               count(*) FILTER (WHERE status <> 'PENDING') AS decided,
               count(*) FILTER (WHERE status = 'REJECTED') AS denied
        FROM approval_request
        WHERE requested_by ~ '^agent:[0-9a-fA-F-]{36}:' AND created_at >= ? AND created_at < ?
        GROUP BY 1
        """;

    private static final String CHARGES_SQL =
            """
        SELECT agent_id, COALESCE(SUM(unit_price_minor),0) AS minor, MAX(currency) AS currency, count(DISTINCT currency) AS n
        FROM resolution_event
        WHERE billable AND disputed_at IS NULL AND resolved_at >= ? AND resolved_at < ?
        GROUP BY agent_id
        """;

    private final JdbcTemplate jdbc;

    public RoiJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- shared ------------------------------------------------------------------------------

    boolean agentExists(UUID agentId) {
        Integer n =
                jdbc.queryForObject("SELECT count(*) FROM agent WHERE id = ?", Integer.class, agentId);
        return n != null && n != 0;
    }

    // ---- prices ------------------------------------------------------------------------------

    List<PriceResponse> listPrices() {
        return jdbc.query(
                "SELECT id, provider_name, model_prefix, input_per_million, output_per_million,"
                        + " cache_write_per_million, cache_read_per_million, effective_from, set_at, set_by, note"
                        + " FROM llm_price ORDER BY provider_name, model_prefix, effective_from DESC",
                (rs, n) ->
                        new PriceResponse(
                                rs.getObject("id", UUID.class),
                                rs.getString("provider_name"),
                                rs.getString("model_prefix"),
                                rs.getBigDecimal("input_per_million"),
                                rs.getBigDecimal("output_per_million"),
                                rs.getBigDecimal("cache_write_per_million"),
                                rs.getBigDecimal("cache_read_per_million"),
                                rs.getObject("effective_from", LocalDate.class),
                                rs.getTimestamp("set_at").toInstant(),
                                rs.getString("set_by"),
                                rs.getString("note")));
    }

    void upsertPrice(
            UUID id,
            String provider,
            String prefix,
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheWrite,
            BigDecimal cacheRead,
            LocalDate effective,
            String subject,
            String note) {
        jdbc.update(
                "INSERT INTO llm_price (id, provider_name, model_prefix, input_per_million, output_per_million,"
                        + " cache_write_per_million, cache_read_per_million, effective_from, set_by, note)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT (provider_name, model_prefix, effective_from) DO UPDATE SET"
                        + " input_per_million = EXCLUDED.input_per_million,"
                        + " output_per_million = EXCLUDED.output_per_million,"
                        + " cache_write_per_million = EXCLUDED.cache_write_per_million,"
                        + " cache_read_per_million = EXCLUDED.cache_read_per_million,"
                        + " set_by = EXCLUDED.set_by, set_at = now(), note = EXCLUDED.note",
                id,
                provider,
                prefix,
                input,
                output,
                cacheWrite,
                cacheRead,
                effective,
                subject,
                note);
    }

    boolean deletePrice(UUID id) {
        return jdbc.update("DELETE FROM llm_price WHERE id = ?", id) > 0;
    }

    // ---- budget alerts -----------------------------------------------------------------------

    List<Integer> firedThresholds(LocalDate monthStart) {
        return jdbc.query(
                "SELECT threshold FROM roi_budget_alert WHERE month_start = ? ORDER BY threshold",
                (rs, n) -> rs.getInt(1),
                java.sql.Date.valueOf(monthStart));
    }

    /** Returns 0 when another replica already recorded this (month, threshold). */
    int insertBudgetAlert(LocalDate monthStart, int threshold, BigDecimal spendUsd, BigDecimal capUsd) {
        return jdbc.update(
                "INSERT INTO roi_budget_alert (month_start, threshold, spend_usd, cap_usd) VALUES (?,?,?,?)"
                        + " ON CONFLICT (month_start, threshold) DO NOTHING",
                java.sql.Date.valueOf(monthStart),
                threshold,
                spendUsd,
                capUsd);
    }

    // ---- baselines ---------------------------------------------------------------------------

    BaselineResponse findBaseline(UUID agentId) {
        return jdbc.query(
                "SELECT job_type, baseline_period, monthly_volume, avg_handling_minutes, missed_rate,"
                        + " avg_value_per_conversion, value_currency, entered_by, entered_at, updated_at"
                        + " FROM agent_pilot_baseline WHERE agent_id = ?",
                rs ->
                        rs.next()
                                ? new BaselineResponse(
                                        agentId,
                                        rs.getString("job_type"),
                                        rs.getString("baseline_period"),
                                        rs.getBigDecimal("monthly_volume"),
                                        rs.getBigDecimal("avg_handling_minutes"),
                                        rs.getBigDecimal("missed_rate"),
                                        rs.getBigDecimal("avg_value_per_conversion"),
                                        rs.getString("value_currency"),
                                        rs.getString("entered_by"),
                                        rs.getTimestamp("entered_at").toInstant(),
                                        rs.getTimestamp("updated_at").toInstant(),
                                        PilotDtos.TENANT_ENTERED)
                                : new BaselineResponse(
                                        agentId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PilotDtos.TENANT_ENTERED),
                agentId);
    }

    void deleteBaseline(UUID agentId) {
        jdbc.update("DELETE FROM agent_pilot_baseline WHERE agent_id = ?", agentId);
    }

    void upsertBaseline(
            UUID agentId,
            String job,
            String period,
            BigDecimal volume,
            BigDecimal minutes,
            BigDecimal missed,
            BigDecimal value,
            String currency,
            String subject) {
        jdbc.update(
                "INSERT INTO agent_pilot_baseline (agent_id, job_type, baseline_period, monthly_volume,"
                        + " avg_handling_minutes, missed_rate, avg_value_per_conversion, value_currency, entered_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT (agent_id) DO UPDATE SET job_type = EXCLUDED.job_type,"
                        + " baseline_period = EXCLUDED.baseline_period, monthly_volume = EXCLUDED.monthly_volume,"
                        + " avg_handling_minutes = EXCLUDED.avg_handling_minutes, missed_rate = EXCLUDED.missed_rate,"
                        + " avg_value_per_conversion = EXCLUDED.avg_value_per_conversion,"
                        + " value_currency = EXCLUDED.value_currency, entered_by = EXCLUDED.entered_by,"
                        + " entered_at = now(), updated_at = now()",
                agentId,
                job,
                period,
                volume,
                minutes,
                missed,
                value,
                currency,
                subject);
    }

    // ---- outcome marks -----------------------------------------------------------------------

    /** Returns 0 when the conversation already has a correction mark. */
    int insertCorrection(UUID id, String conversationKey, UUID agentId, String markedBy, String note) {
        return jdbc.update(
                "INSERT INTO conversation_correction (id, conversation_key, agent_id, marked_by, note)"
                        + " VALUES (?,?,?,?,?) ON CONFLICT (conversation_key) DO NOTHING",
                id,
                conversationKey,
                agentId,
                markedBy,
                note);
    }

    // ---- per-agent table ---------------------------------------------------------------------

    void queryOutcomes(Timestamp start, Timestamp end, RowCallbackHandler handler) {
        jdbc.query(OUTCOMES_SQL, handler, start, end);
    }

    void queryApprovals(Timestamp start, Timestamp end, RowCallbackHandler handler) {
        jdbc.query(APPROVALS_SQL, handler, start, end);
    }

    void queryCharges(Timestamp start, Timestamp end, RowCallbackHandler handler) {
        jdbc.query(CHARGES_SQL, handler, start, end);
    }

    void queryAgentIdsAndNames(RowCallbackHandler handler) {
        jdbc.query("SELECT id, name FROM agent ORDER BY name", handler);
    }

    List<AssumptionResponse> assumptions() {
        return jdbc.query(
                "SELECT agent_id, value_per_resolution, value_currency, minutes_saved_per_resolution, updated_at"
                        + " FROM agent_value_assumption",
                (rs, n) ->
                        new AssumptionResponse(
                                rs.getObject("agent_id", UUID.class),
                                rs.getBigDecimal("value_per_resolution"),
                                rs.getString("value_currency"),
                                rs.getBigDecimal("minutes_saved_per_resolution"),
                                rs.getTimestamp("updated_at").toInstant(),
                                RoiMetrics.ROI_BASIS));
    }

    void deleteAssumption(UUID agentId) {
        jdbc.update("DELETE FROM agent_value_assumption WHERE agent_id = ?", agentId);
    }

    void upsertAssumption(
            UUID agentId, BigDecimal value, String currency, BigDecimal minutes, String subject) {
        jdbc.update(
                "INSERT INTO agent_value_assumption (agent_id, value_per_resolution, value_currency,"
                        + " minutes_saved_per_resolution, updated_by) VALUES (?,?,?,?,?)"
                        + " ON CONFLICT (agent_id) DO UPDATE SET value_per_resolution = EXCLUDED.value_per_resolution,"
                        + " value_currency = EXCLUDED.value_currency,"
                        + " minutes_saved_per_resolution = EXCLUDED.minutes_saved_per_resolution,"
                        + " updated_by = EXCLUDED.updated_by, updated_at = now()",
                agentId,
                value,
                currency,
                minutes,
                subject);
    }

    // ---- cost --------------------------------------------------------------------------------

    void queryGrains(Timestamp start, Timestamp end, RowCallbackHandler handler) {
        jdbc.query(
                "SELECT (l.started_at AT TIME ZONE 'UTC')::date AS day, l.agent_id, wr.workflow_id, cs.channel,"
                        + " a.created_by AS owner, l.provider_name, l.model, count(*) AS calls,"
                        + " count(*) FILTER (WHERE NOT l.success) AS failed,"
                        + " count(*) FILTER (WHERE l.success AND (l.prompt_tokens IS NULL OR l.completion_tokens IS NULL)) AS no_usage,"
                        + " COALESCE(sum(l.prompt_tokens),0) AS p, COALESCE(sum(l.completion_tokens),0) AS c,"
                        + " COALESCE(sum(l.cache_creation_tokens),0) AS cc, COALESCE(sum(l.cache_read_tokens),0) AS cr,"
                        + " COALESCE(sum(l.reasoning_tokens),0) AS r"
                        + " FROM llm_call_log l"
                        + " LEFT JOIN agent a ON a.id = l.agent_id"
                        + " LEFT JOIN workflow_run wr ON wr.id = CASE WHEN l.session_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'"
                        + "   THEN l.session_id::uuid END"
                        + " LEFT JOIN LATERAL (SELECT channel FROM channel_session WHERE session_id = l.session_id LIMIT 1) cs ON true"
                        + " WHERE l.started_at >= ? AND l.started_at < ?"
                        + " GROUP BY 1,2,3,4,5,6,7",
                handler,
                start,
                end);
    }

    /** One row: minor, currency, n (distinct currencies). The handler is called once. */
    void queryPlatformCharges(Timestamp start, Timestamp end, RowCallbackHandler handler) {
        jdbc.query(
                "SELECT COALESCE(SUM(unit_price_minor),0) AS minor, MAX(currency) AS currency, count(DISTINCT currency) AS n"
                        + " FROM resolution_event WHERE billable AND disputed_at IS NULL AND resolved_at >= ? AND resolved_at < ?",
                handler,
                start,
                end);
    }

    /** Rows of (id::text, name) for the dimension; nothing for dimensions with no name table. */
    void queryDimensionLabels(String dim, RowCallbackHandler handler) {
        String sql =
                switch (dim) {
                    case "agent" -> "SELECT id::text, name FROM agent";
                    case "workflow" -> "SELECT id::text, name FROM workflow_definition";
                    default -> null;
                };
        if (sql != null) {
            jdbc.query(sql, handler);
        }
    }

    // ---- pilot report ------------------------------------------------------------------------

    Optional<String> demoLabel() {
        return jdbc.query(
                "SELECT label FROM demo_dataset WHERE id = 1",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty());
    }

    void queryLatencyMedian(UUID agentId, Timestamp start, Timestamp end, RowCallbackHandler handler) {
        jdbc.query(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) AS med, count(*) AS n"
                        + " FROM llm_call_log WHERE agent_id = ? AND success AND started_at >= ? AND started_at < ?",
                handler,
                agentId,
                start,
                end);
    }

    Long countModelCalls(UUID agentId, Timestamp start, Timestamp end) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM llm_call_log WHERE agent_id = ? AND started_at >= ? AND started_at < ?",
                Long.class,
                agentId,
                start,
                end);
    }

    Integer countBillableCurrencies(UUID agentId, Timestamp start, Timestamp end) {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT currency) FROM resolution_event WHERE billable AND disputed_at IS NULL"
                        + " AND agent_id = ? AND resolved_at >= ? AND resolved_at < ?",
                Integer.class,
                agentId,
                start,
                end);
    }

    <T> List<T> queryModelsUsed(UUID agentId, Timestamp start, Timestamp end, RowMapper<T> mapper) {
        return jdbc.query(
                "SELECT DISTINCT (started_at AT TIME ZONE 'UTC')::date AS day, provider_name, model FROM llm_call_log"
                        + " WHERE agent_id = ? AND success AND started_at >= ? AND started_at < ?"
                        + " ORDER BY provider_name, model, day",
                mapper,
                agentId,
                start,
                end);
    }
}
