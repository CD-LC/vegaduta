package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.PilotDtos.AssumptionLine;
import com.agenticai.core.common.roi.PilotDtos.BaselineResponse;
import com.agenticai.core.common.roi.PilotDtos.DemoStatus;
import com.agenticai.core.common.roi.PilotDtos.PilotReport;
import com.agenticai.core.common.roi.RoiDtos.AgentRoiResponse;
import com.agenticai.core.common.roi.RoiDtos.AgentRoiRow;
import com.agenticai.core.common.roi.RoiDtos.PriceResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * One agent's pilot report data over a date range. Composed only of what the platform recorded
 * (conversations, outcomes, call latency, cost) and of numbers the tenant typed (baseline, value
 * assumptions, model prices); the latter are listed in {@link PilotReport#assumptions()} so the
 * report can print an assumptions box. It adds no benchmark, target or claim from outside the data.
 *
 * <p>Reuses {@link RoiAgentService#table} for every figure, so the report cannot disagree with the
 * Cost &amp; ROI page. Tenant scoping is the schema-per-tenant connection (no id from the caller).
 * Transactional for the same reason as the other ROI services.
 */
@Service
@Transactional(readOnly = true)
public class PilotReportService {

    private final JdbcTemplate jdbc;
    private final RoiAgentService agents;
    private final RoiBaselineService baselines;
    private final RoiPriceService prices;

    public PilotReportService(
            JdbcTemplate jdbc,
            RoiAgentService agents,
            RoiBaselineService baselines,
            RoiPriceService prices) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.baselines = baselines;
        this.prices = prices;
    }

    public DemoStatus demo() {
        return jdbc.query(
                "SELECT label FROM demo_dataset WHERE id = 1",
                rs -> rs.next() ? new DemoStatus(true, rs.getString(1)) : new DemoStatus(false, null));
    }

    public PilotReport build(
            UUID agentId, LocalDate from, LocalDate to, boolean includeAssumed, Instant now) {
        AgentRoiResponse table = agents.table(from, to, includeAssumed);
        AgentRoiRow row =
                table.agents().stream()
                        .filter(a -> a.agentId().equals(agentId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "No such agent for this tenant"));
        BaselineResponse baseline = baselines.find(agentId);
        DemoStatus demo = demo();

        Timestamp start = Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        BigDecimal[] median = new BigDecimal[1];
        long[] samples = new long[1];
        jdbc.query(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) AS med, count(*) AS n"
                        + " FROM llm_call_log WHERE agent_id = ? AND success AND started_at >= ? AND started_at < ?",
                rs -> {
                    samples[0] = rs.getLong("n");
                    double ms = rs.getDouble("med");
                    median[0] =
                            samples[0] == 0 || rs.wasNull()
                                    ? null
                                    : BigDecimal.valueOf(ms)
                                            .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
                },
                agentId,
                start,
                end);

        Long calls =
                jdbc.queryForObject(
                        "SELECT count(*) FROM llm_call_log WHERE agent_id = ? AND started_at >= ? AND started_at < ?",
                        Long.class,
                        agentId,
                        start,
                        end);
        long modelCalls = calls == null ? 0 : calls;
        Integer currencies =
                jdbc.queryForObject(
                        "SELECT count(DISTINCT currency) FROM resolution_event WHERE billable AND disputed_at IS NULL"
                                + " AND agent_id = ? AND resolved_at >= ? AND resolved_at < ?",
                        Integer.class,
                        agentId,
                        start,
                        end);
        boolean mixed = currencies != null && currencies > 1;

        long conversations = row.conversations();
        // No model call recorded means "nothing measured", not "costs nothing": null, shown as a dash.
        BigDecimal cost = modelCalls == 0 ? null : row.estimatedCostUsd();
        BigDecimal perResolved = modelCalls == 0 ? null : row.estimatedCostPerResolvedUsd();
        // Charges in several currencies cannot be summed; ROI would silently ignore them, so withhold it.
        BigDecimal net = mixed ? null : row.netValue();
        BigDecimal roiPct = mixed ? null : row.roiPercent();
        String roiNote = mixed ? "CHARGES_MIXED_CURRENCY" : row.roiNote();
        return new PilotReport(
                demo.demo(),
                demo.label(),
                agentId,
                row.agentName(),
                from,
                to,
                now,
                includeAssumed,
                conversations,
                row.resolved(),
                row.handedToHuman(),
                row.humanCorrected(),
                RoiMetrics.ratio(row.resolved(), conversations),
                row.handoverRate(),
                row.humanCorrectionRate(),
                median[0],
                samples[0],
                cost,
                row.costComplete(),
                perResolved,
                row.platformChargesMinor(),
                row.platformChargesCurrency(),
                row.valueTotal(),
                row.minutesSavedTotal(),
                net,
                roiPct,
                roiNote,
                RoiMetrics.ROI_BASIS,
                baseline,
                assumptions(row, baseline, includeAssumed, modelsUsed(agentId, start, end)),
                modelCalls,
                row.valueCurrency(),
                mixed);
    }

    private record Used(LocalDate day, String provider, String model) {}

    private List<Used> modelsUsed(UUID agentId, Timestamp start, Timestamp end) {
        return jdbc.query(
                "SELECT DISTINCT (started_at AT TIME ZONE 'UTC')::date AS day, provider_name, model FROM llm_call_log"
                        + " WHERE agent_id = ? AND success AND started_at >= ? AND started_at < ?"
                        + " ORDER BY provider_name, model, day",
                (rs, n) -> new Used(rs.getObject("day", LocalDate.class), rs.getString(2), rs.getString(3)),
                agentId,
                start,
                end);
    }

    /** Every number in the report that a person typed, in the order the box prints them. */
    List<AssumptionLine> assumptions(
            AgentRoiRow row, BaselineResponse b, boolean includeAssumed, List<Used> used) {
        List<AssumptionLine> out = new ArrayList<>();
        String src = PilotDtos.TENANT_ENTERED;
        if (row.valuePerResolution() != null) {
            out.add(
                    new AssumptionLine(
                            "Value of one resolved conversation",
                            money(row.valuePerResolution(), row.valueCurrency()),
                            src));
        }
        if (row.minutesSavedPerResolution() != null) {
            out.add(
                    new AssumptionLine(
                            "Minutes saved per resolved conversation",
                            plain(row.minutesSavedPerResolution()),
                            src));
        }
        if (b.jobType() != null) {
            out.add(new AssumptionLine("Pilot job", b.jobType(), src));
        }
        if (b.baselinePeriod() != null) {
            out.add(new AssumptionLine("Baseline period", b.baselinePeriod(), src));
        }
        if (b.monthlyVolume() != null) {
            out.add(new AssumptionLine("Baseline monthly volume", plain(b.monthlyVolume()), src));
        }
        if (b.avgHandlingMinutes() != null) {
            out.add(
                    new AssumptionLine(
                            "Baseline average handling time",
                            plain(b.avgHandlingMinutes()) + " min",
                            src));
        }
        if (b.missedRate() != null) {
            out.add(
                    new AssumptionLine(
                            "Baseline missed rate",
                            b.missedRate()
                                            .multiply(BigDecimal.valueOf(100))
                                            .setScale(1, RoundingMode.HALF_UP)
                                            .toPlainString()
                                    + "%",
                            src));
        }
        if (b.avgValuePerConversion() != null) {
            out.add(
                    new AssumptionLine(
                            "Baseline average value per converted enquiry",
                            money(b.avgValuePerConversion(), b.valueCurrency()),
                            src + "; shown for reference, not used in any figure"));
        }
        out.addAll(priceLines(used));
        out.add(
                new AssumptionLine(
                        "Idle-closed conversations counted as resolved",
                        includeAssumed ? "yes" : "no",
                        "report setting"));
        return out;
    }

    /**
     * The prices that actually priced this agent's successful calls, chosen by the same rule the cost
     * uses ({@link RoiCostModel#select}: provider, longest model prefix, latest effective date not
     * after the call's day). A model with no applicable price gets an explicit "none entered" line, so
     * the box matches the cost section's lower-bound wording.
     */
    private List<AssumptionLine> priceLines(List<Used> used) {
        List<RoiCostModel.Rate> rates = prices.rates();
        Map<UUID, RoiCostModel.Rate> selected = new LinkedHashMap<>();
        Map<String, Boolean> unpriced = new LinkedHashMap<>();
        for (Used u : used) {
            var rate = RoiCostModel.select(rates, u.provider(), u.model(), u.day());
            if (rate.isPresent()) {
                selected.putIfAbsent(rate.get().id(), rate.get());
            } else {
                unpriced.putIfAbsent(u.provider() + " / " + u.model(), Boolean.TRUE);
            }
        }
        List<AssumptionLine> out = new ArrayList<>();
        for (RoiCostModel.Rate p : selected.values()) {
            out.add(
                    new AssumptionLine(
                            "Model price "
                                    + p.providerName()
                                    + " / "
                                    + p.modelPrefix()
                                    + " from "
                                    + p.effectiveFrom()
                                    + " (USD per million tokens)",
                            "in "
                                    + rate(p.inputPerMillion())
                                    + ", out "
                                    + rate(p.outputPerMillion())
                                    + ", cache write "
                                    + rate(p.cacheWritePerMillion())
                                    + ", cache read "
                                    + rate(p.cacheReadPerMillion()),
                            PilotDtos.TENANT_ENTERED));
        }
        for (String model : unpriced.keySet()) {
            out.add(
                    new AssumptionLine(
                            "Model price " + model,
                            "none entered, so these calls are not in the cost",
                            "missing"));
        }
        return out;
    }

    private static String rate(BigDecimal r) {
        return r == null ? "not set" : plain(r);
    }

    static String plain(BigDecimal n) {
        BigDecimal s = n.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    static String money(BigDecimal n, String currency) {
        return plain(n) + (currency == null ? "" : " " + currency);
    }
}
