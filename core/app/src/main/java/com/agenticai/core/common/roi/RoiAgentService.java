package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.RoiDtos.AgentRoiResponse;
import com.agenticai.core.common.roi.RoiDtos.AgentRoiRow;
import com.agenticai.core.common.roi.RoiDtos.AssumptionRequest;
import com.agenticai.core.common.roi.RoiDtos.AssumptionResponse;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The per-agent table: recorded cost ({@link RoiCostService}), recorded outcomes ({@code
 * resolution_event}, {@code turn_feedback} EDIT rows, {@code conversation_correction}), recorded
 * approval decisions ({@code approval_request}), and the tenant's own value assumptions.
 *
 * <p>A conversation counts once, under the outcome of its LATEST resolution event in the window.
 * "Resolved" means resolved by the agent; the idle-close heuristic ({@code NO_ESCALATION_IDLE},
 * confidence 0.6, "assumed resolved") is counted separately and excluded from {@code resolved}
 * unless the caller opts in, so ROI is not inflated by guesses.
 *
 * <p>Transactional on purpose: a raw {@code JdbcTemplate} only runs in the tenant's schema when it
 * joins the JPA-bound connection that {@code SchemaMultiTenantConnectionProvider} scopes, which
 * needs an open transaction; outside one it would read the default schema. {@code RoiApiIT} fails
 * if that is removed.
 */
@Service
@Transactional(readOnly = true)
public class RoiAgentService {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Za-z]{3}$");
    private static final BigDecimal MAX_VALUE = new BigDecimal("1000000000");

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
    private final RoiCostService costs;

    public RoiAgentService(JdbcTemplate jdbc, RoiCostService costs) {
        this.jdbc = jdbc;
        this.costs = costs;
    }

    private record Outcomes(
            long conversations,
            long confirmed,
            long assumed,
            long byHuman,
            long handed,
            long abandoned,
            long unresolved,
            long corrected) {}

    private record Approvals(long decided, long denied) {}

    private record Charge(long minor, String currency) {}

    public AgentRoiResponse table(LocalDate from, LocalDate to, boolean includeAssumed) {
        Timestamp start = Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        Map<UUID, Outcomes> outcomes = new HashMap<>();
        jdbc.query(
                OUTCOMES_SQL,
                rs -> {
                    outcomes.put(
                            rs.getObject("agent_id", UUID.class),
                            new Outcomes(
                                    rs.getLong("conversations"),
                                    rs.getLong("confirmed"),
                                    rs.getLong("assumed"),
                                    rs.getLong("by_human"),
                                    rs.getLong("handed"),
                                    rs.getLong("abandoned"),
                                    rs.getLong("unresolved"),
                                    rs.getLong("corrected")));
                },
                start,
                end);

        Map<UUID, Approvals> approvals = new HashMap<>();
        jdbc.query(
                APPROVALS_SQL,
                rs -> {
                    approvals.put(
                            rs.getObject("agent_id", UUID.class),
                            new Approvals(rs.getLong("decided"), rs.getLong("denied")));
                },
                start,
                end);

        Map<UUID, Charge> charges = new HashMap<>();
        jdbc.query(
                CHARGES_SQL,
                rs -> {
                    // Mixed currencies for one agent cannot be summed honestly: report none rather
                    // than a wrong total.
                    charges.put(
                            rs.getObject("agent_id", UUID.class),
                            rs.getInt("n") > 1
                                    ? new Charge(0, null)
                                    : new Charge(rs.getLong("minor"), rs.getString("currency")));
                },
                start,
                end);

        Map<UUID, List<RoiCostService.Grain>> grainsByAgent = new HashMap<>();
        for (RoiCostService.Grain g : costs.grains(from, to)) {
            if (g.agentId() != null) {
                grainsByAgent.computeIfAbsent(g.agentId(), k -> new ArrayList<>()).add(g);
            }
        }
        Map<UUID, AssumptionResponse> assumptions = new HashMap<>();
        for (AssumptionResponse a : assumptions()) {
            assumptions.put(a.agentId(), a);
        }

        List<AgentRoiRow> rows = new ArrayList<>();
        jdbc.query(
                "SELECT id, name FROM agent ORDER BY name",
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    rows.add(
                            buildRow(
                                    id,
                                    rs.getString("name"),
                                    outcomes.get(id),
                                    approvals.get(id),
                                    charges.get(id),
                                    grainsByAgent.getOrDefault(id, List.of()),
                                    assumptions.get(id),
                                    includeAssumed));
                });
        return new AgentRoiResponse(from, to, includeAssumed, RoiMetrics.ROI_BASIS, rows);
    }

    static AgentRoiRow buildRow(
            UUID id,
            String name,
            Outcomes o,
            Approvals ap,
            Charge ch,
            List<RoiCostService.Grain> grains,
            AssumptionResponse as,
            boolean includeAssumed) {
        o = o == null ? new Outcomes(0, 0, 0, 0, 0, 0, 0, 0) : o;
        ap = ap == null ? new Approvals(0, 0) : ap;
        ch = ch == null ? new Charge(0, null) : ch;
        RoiDtos.CostRow cost = RoiCostService.row(id.toString(), name, grains);
        long resolved = o.confirmed() + (includeAssumed ? o.assumed() : 0);
        RoiMetrics.Assumption assumption =
                as == null
                        ? null
                        : new RoiMetrics.Assumption(
                                as.valuePerResolution(),
                                as.valueCurrency(),
                                as.minutesSavedPerResolution());
        RoiMetrics.Roi roi =
                RoiMetrics.roi(
                        resolved,
                        assumption,
                        new RoiMetrics.CostBasis(
                                cost.byokEstimatedCostUsd(),
                                ch.minor(),
                                ch.currency(),
                                cost.costComplete()));
        return new AgentRoiRow(
                id,
                name,
                o.conversations(),
                resolved,
                o.confirmed(),
                o.assumed(),
                o.byHuman(),
                o.handed(),
                o.abandoned(),
                o.unresolved(),
                o.corrected(),
                RoiMetrics.ratio(o.handed(), o.conversations()),
                RoiMetrics.ratio(o.corrected(), o.conversations()),
                ap.decided(),
                ap.denied(),
                RoiMetrics.ratio(ap.denied(), ap.decided()),
                cost.byokEstimatedCostUsd(),
                cost.platformHostedEstimatedCostUsd(),
                cost.estimatedCostUsd(),
                RoiMetrics.perUnit(cost.estimatedCostUsd(), resolved),
                cost.costComplete(),
                ch.minor(),
                ch.currency(),
                as == null ? null : as.valuePerResolution(),
                as == null ? null : as.valueCurrency(),
                as == null ? null : as.minutesSavedPerResolution(),
                roi.valueTotal(),
                roi.minutesSavedTotal(),
                roi.netValue(),
                roi.roiPercent(),
                roi.note());
    }

    // ---- assumptions ------------------------------------------------------------------------

    public List<AssumptionResponse> assumptions() {
        return jdbc.query(
                "SELECT agent_id, value_per_resolution, value_currency, minutes_saved_per_resolution, updated_at"
                        + " FROM agent_value_assumption",
                (rs, n) ->
                        new AssumptionResponse(
                                rs.getObject("agent_id", UUID.class),
                                        rs.getBigDecimal("value_per_resolution"),
                                rs.getString("value_currency"),
                                        rs.getBigDecimal("minutes_saved_per_resolution"),
                                rs.getTimestamp("updated_at").toInstant(), RoiMetrics.ROI_BASIS));
    }

    public AssumptionResponse assumption(UUID agentId) {
        requireAgent(agentId);
        return assumptions().stream()
                .filter(a -> a.agentId().equals(agentId))
                .findFirst()
                .orElse(
                        new AssumptionResponse(
                                agentId, null, null, null, null, RoiMetrics.ROI_BASIS));
    }

    /**
     * Stores exactly what the tenant sent. Sending all-null removes the assumption; nothing is
     * defaulted.
     */
    @Transactional
    public AssumptionResponse setAssumption(UUID agentId, AssumptionRequest req, String subject) {
        requireAgent(agentId);
        BigDecimal value = req == null ? null : req.valuePerResolution();
        BigDecimal minutes = req == null ? null : req.minutesSavedPerResolution();
        String currency =
                req == null || req.valueCurrency() == null
                        ? null
                        : req.valueCurrency().trim().toUpperCase();
        if (value == null && minutes == null) {
            jdbc.update("DELETE FROM agent_value_assumption WHERE agent_id = ?", agentId);
            return assumption(agentId);
        }
        if ((value != null && value.signum() < 0)
                || (minutes != null && minutes.signum() < 0)
                || (value != null && value.compareTo(MAX_VALUE) > 0)
                || (minutes != null && minutes.compareTo(MAX_VALUE) > 0)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "values must be between 0 and " + MAX_VALUE.toPlainString());
        }
        if (value != null && (currency == null || !CURRENCY.matcher(currency).matches())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "valueCurrency (ISO 4217, e.g. USD) is required with valuePerResolution");
        }
        jdbc.update(
                "INSERT INTO agent_value_assumption (agent_id, value_per_resolution, value_currency,"
                        + " minutes_saved_per_resolution, updated_by) VALUES (?,?,?,?,?)"
                        + " ON CONFLICT (agent_id) DO UPDATE SET value_per_resolution = EXCLUDED.value_per_resolution,"
                        + " value_currency = EXCLUDED.value_currency,"
                        + " minutes_saved_per_resolution = EXCLUDED.minutes_saved_per_resolution,"
                        + " updated_by = EXCLUDED.updated_by, updated_at = now()",
                agentId,
                value,
                value == null ? null : currency,
                minutes,
                subject);
        return assumption(agentId);
    }

    private void requireAgent(UUID agentId) {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM agent WHERE id = ?", Integer.class, agentId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No such agent for this tenant");
        }
    }
}
