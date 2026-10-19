package com.agenticai.core.common.roi;

import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.chat.LlmSpendReader;
import com.agenticai.core.chat.gateway.LlmGatewayBudgetService;
import com.agenticai.core.common.roi.RoiDtos.BudgetResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spend against the monthly budget the gateway already enforces ({@link LlmGatewayBudgetService}),
 * read from the counter that enforcement reads ({@code llm_spend_month}). That counter is priced by
 * {@code LlmPricingCatalog}, which falls back to a fixed rate for models it does not list, so the
 * bar is labelled as the enforcement figure: it is the number that will actually block calls, which
 * is what a budget bar must show, and it can differ from the per-agent estimated cost that uses
 * only the tenant's own prices.
 *
 * <p>{@link #raiseThresholdAlerts} writes one {@code roi_budget_alert} row per (month, 80/100) and,
 * only when the row is new, one bell notification through {@link NotificationService} - the same
 * path {@code PerUserBudgetService} uses - so each threshold alerts once a month.
 */
@Service
public class RoiBudgetService {

    private static final Logger log = LoggerFactory.getLogger(RoiBudgetService.class);
    static final String SPEND_BASIS =
            "enforcement counter (llm_spend_month): the figure the monthly cap is checked against";
    static final List<Integer> THRESHOLDS = List.of(80, 100);

    private final JdbcTemplate jdbc;
    private final LlmGatewayBudgetService gatewayBudget;
    private final LlmSpendReader spendReader;
    private final TenantLimits tenantLimits;
    private final NotificationService notifications;

    public RoiBudgetService(
            JdbcTemplate jdbc,
            LlmGatewayBudgetService gatewayBudget,
            LlmSpendReader spendReader,
            TenantLimits tenantLimits,
            NotificationService notifications) {
        this.jdbc = jdbc;
        this.gatewayBudget = gatewayBudget;
        this.spendReader = spendReader;
        this.tenantLimits = tenantLimits;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public BudgetResponse status() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        LocalDate monthStart = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        Optional<BigDecimal> cap =
                tenantId == null ? Optional.empty() : gatewayBudget.monthlyCapUsd(tenantId);
        BigDecimal spent = spendReader.monthlySpend(monthStart);
        if (spent == null) {
            spent = spendReader.costSince(monthStart.atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        BigDecimal capUsd = cap.filter(c -> c.signum() > 0).orElse(null);
        BigDecimal percent =
                capUsd == null || spent == null
                        ? null
                        : spent.multiply(BigDecimal.valueOf(100))
                                .divide(capUsd, 1, RoundingMode.HALF_UP);
        List<Integer> fired =
                jdbc.query(
                        "SELECT threshold FROM roi_budget_alert WHERE month_start = ? ORDER BY threshold",
                        (rs, n) -> rs.getInt(1),
                        java.sql.Date.valueOf(monthStart));
        String plan = tenantId == null ? null : tenantLimits.plan(tenantId).name();
        return new BudgetResponse(
                monthStart, plan, spent, capUsd, percent, capUsd != null, SPEND_BASIS, fired);
    }

    /**
     * Raises any due 80%/100% alert for the current tenant context. Returns how many were raised.
     */
    @Transactional
    public int raiseThresholdAlerts() {
        BudgetResponse b = status();
        if (!b.capConfigured() || b.spentUsd() == null) {
            return 0;
        }
        int raised = 0;
        for (int threshold : THRESHOLDS) {
            BigDecimal limit = b.capUsd().multiply(BigDecimal.valueOf(threshold)).movePointLeft(2);
            if (b.spentUsd().compareTo(limit) < 0 || b.alertsFired().contains(threshold)) {
                continue;
            }
            int inserted =
                    jdbc.update(
                            "INSERT INTO roi_budget_alert (month_start, threshold, spend_usd, cap_usd) VALUES (?,?,?,?)"
                                    + " ON CONFLICT (month_start, threshold) DO NOTHING",
                            java.sql.Date.valueOf(b.monthStart()),
                            threshold,
                            b.spentUsd(),
                            b.capUsd());
            if (inserted == 0) {
                continue; // another replica raised it first
            }
            try {
                notifications.create(
                        null,
                        NotificationCategory.BILLING,
                        title(threshold),
                        body(threshold, b),
                        threshold >= 100
                                ? NotificationSeverity.ERROR
                                : NotificationSeverity.WARNING,
                        "/roi",
                        null);
            } catch (RuntimeException e) {
                log.warn(
                        "Budget alert {}% recorded but the notification failed: {}",
                        threshold, e.getMessage());
            }
            raised++;
        }
        return raised;
    }

    static String title(int threshold) {
        return threshold >= 100
                ? "Monthly AI budget reached"
                : "Monthly AI budget at " + threshold + "%";
    }

    static String body(int threshold, BudgetResponse b) {
        return "Spend this month is $"
                + b.spentUsd().setScale(2, RoundingMode.HALF_UP)
                + " of the $"
                + b.capUsd().setScale(2, RoundingMode.HALF_UP)
                + " monthly cap ("
                + threshold
                + "% threshold)."
                + (threshold >= 100
                        ? " New LLM calls are blocked until the cap is raised or the month rolls over."
                        : "");
    }
}
