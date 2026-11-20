package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.PilotDtos.BaselineRequest;
import com.agenticai.core.common.roi.PilotDtos.BaselineResponse;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
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
    // Column limits of agent_pilot_baseline (V497): NUMERIC(12,0), (10,2), (5,4), (14,4).
    // Validation matches them so
    // a number is either stored exactly as typed or rejected with a 400, never rounded or turned
    // into a 500.
    private static final BigDecimal MAX_VOLUME = new BigDecimal("999999999999");
    private static final BigDecimal MAX_MINUTES = new BigDecimal("99999999.99");
    private static final BigDecimal MAX_VALUE = new BigDecimal("9999999999.9999");

    private final RoiJdbcRepository repository;

    public RoiBaselineService(RoiJdbcRepository repository) {
        this.repository = repository;
    }

    public BaselineResponse get(UUID agentId) {
        requireAgent(agentId);
        return find(agentId);
    }

    /** The stored baseline, or an all-null one when the tenant has not entered any. */
    BaselineResponse find(UUID agentId) {
        return repository.findBaseline(agentId);
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
            repository.deleteBaseline(agentId);
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
            throw bad(
                    "missedRate is a fraction between 0 and 1 with at most 4 decimals (0.25 = 25%)");
        }
        if (value != null && (currency == null || !CURRENCY.matcher(currency).matches())) {
            throw bad("valueCurrency (ISO 4217, e.g. INR) is required with avgValuePerConversion");
        }
        repository.upsertBaseline(
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
        if (!repository.agentExists(agentId)) {
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
