package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.PilotDtos.BaselineRequest;
import com.agenticai.core.common.roi.PilotDtos.BaselineResponse;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant's own pre-pilot numbers for one agent ({@code agent_pilot_baseline}, V497). Stores
 * exactly what the tenant sent; sending every field empty removes the baseline. Nothing is measured
 * or defaulted by the platform.
 *
 * <p>Transactional on purpose, same reason as {@link RoiAgentService}: a raw {@code JdbcTemplate}
 * only runs in the tenant's schema when it joins the JPA-bound connection.
 */
@Service
@Transactional(readOnly = true)
public class RoiBaselineService {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Za-z]{3}$");
    // Column limits of agent_pilot_baseline (V497): NUMERIC(12,0), (10,2), (5,4), (14,4). Validation matches them so
    // a number is either stored exactly as typed or rejected with a 400, never rounded or turned into a 500.
    private static final BigDecimal MAX_VOLUME = new BigDecimal("999999999999");
    private static final BigDecimal MAX_MINUTES = new BigDecimal("99999999.99");
    private static final BigDecimal MAX_VALUE = new BigDecimal("9999999999.9999");

    private final JdbcTemplate jdbc;

    public RoiBaselineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BaselineResponse get(UUID agentId) {
        requireAgent(agentId);
        return find(agentId);
    }

    /** The stored baseline, or an all-null one when the tenant has not entered any. */
    BaselineResponse find(UUID agentId) {
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

    @Transactional
    public BaselineResponse put(UUID agentId, BaselineRequest req, String subject) {
        requireAgent(agentId);
        String job = blankToNull(req == null ? null : req.jobType());
        String period = blankToNull(req == null ? null : req.baselinePeriod());
        BigDecimal volume = req == null ? null : req.monthlyVolume();
        BigDecimal minutes = req == null ? null : req.avgHandlingMinutes();
        BigDecimal missed = req == null ? null : req.missedRate();
        BigDecimal value = req == null ? null : req.avgValuePerConversion();
        String currency =
                req == null || blankToNull(req.valueCurrency()) == null
                        ? null
                        : req.valueCurrency().trim().toUpperCase();

        if (job == null
                && period == null
                && volume == null
                && minutes == null
                && missed == null
                && value == null) {
            jdbc.update("DELETE FROM agent_pilot_baseline WHERE agent_id = ?", agentId);
            return find(agentId);
        }
        job = job == null ? null : job.toUpperCase();
        if (job != null && !PilotDtos.JOB_TYPES.contains(job)) {
            throw bad("jobType must be one of " + PilotDtos.JOB_TYPES);
        }
        if (period != null && period.length() > 80) {
            throw bad("baselinePeriod is limited to 80 characters");
        }
        range("monthlyVolume", volume, MAX_VOLUME, 0);
        range("avgHandlingMinutes", minutes, MAX_MINUTES, 2);
        range("avgValuePerConversion", value, MAX_VALUE, 4);
        if (missed != null
                && (missed.signum() < 0
                        || missed.compareTo(BigDecimal.ONE) > 0
                        || missed.stripTrailingZeros().scale() > 4)) {
            throw bad("missedRate is a fraction between 0 and 1 with at most 4 decimals (0.25 = 25%)");
        }
        if (value != null && (currency == null || !CURRENCY.matcher(currency).matches())) {
            throw bad("valueCurrency (ISO 4217, e.g. INR) is required with avgValuePerConversion");
        }
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
                value == null ? null : currency,
                subject);
        return find(agentId);
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

    private static void range(String field, BigDecimal n, BigDecimal max, int scale) {
        if (n == null) {
            return;
        }
        if (n.signum() < 0 || n.compareTo(max) > 0) {
            throw bad(field + " must be between 0 and " + max.toPlainString());
        }
        if (n.stripTrailingZeros().scale() > scale) {
            throw bad(field + " allows at most " + scale + " decimal places");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
