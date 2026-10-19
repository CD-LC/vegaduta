package com.agenticai.core.platform.spendcap;

import com.agenticai.core.agents.spendcap.SpendCapAlertSink;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.billing.email.BillingEmailService;
import com.agenticai.core.platform.ops.PlatformTenantAuditor;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers a spend-cap threshold crossing (once per tenant, month and stage -
 * see {@code SpendCapService#claimStage}): an email to every tenant admin and a
 * platform issue in the tenant's own {@code platform_issue} table, which the
 * admin console's open-alert count and issue list already sweep across tenants.
 * The two are independent: a failed email never stops the issue, and vice versa.
 */
@Component
public class SpendCapAlertNotifier implements SpendCapAlertSink {

    private static final Logger log = LoggerFactory.getLogger(SpendCapAlertNotifier.class);
    private static final String SYSTEM_ACTOR = "system:spend-cap";
    private static final DateTimeFormatter RESET_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final BillingEmailService emailService;
    private final PlatformIssueService issueService;

    public SpendCapAlertNotifier(BillingEmailService emailService, PlatformIssueService issueService) {
        this.emailService = emailService;
        this.issueService = issueService;
    }

    @Override
    public void onThresholdCrossed(Alert alert) {
        String resetsOn = alert.monthStart().plusMonths(1).format(RESET_FORMAT);
        try {
            int sent = emailService.sendSpendCapAlertEmail(alert.tenantId(), alert.tenantName(), alert.stage(),
                alert.monthToDateUsd(), alert.capUsd(), resetsOn);
            log.info("Spend-cap {}% alert for tenant {} emailed to {} admin(s)", alert.stage(), alert.tenantId(), sent);
        } catch (RuntimeException e) {
            log.warn("Spend-cap {}% alert email failed for tenant {}", alert.stage(), alert.tenantId(), e);
        }
        try {
            String title = alert.stage() >= 100
                ? "Monthly hosted spend cap reached - hosted LLM calls blocked"
                : "Monthly hosted spend cap 80% used";
            String message = "Tenant " + alert.tenantName() + " (" + alert.tenantId() + ") has used $"
                + alert.monthToDateUsd().toPlainString() + " of its $" + alert.capUsd().toPlainString()
                + " hosted-pool cap for " + alert.monthStart().getYear() + "-"
                + String.format("%02d", alert.monthStart().getMonthValue())
                + (alert.stage() >= 100 ? ". Hosted-pool calls are rejected (402) until " + resetsOn
                    + " or until the cap is raised. BYOK is unaffected." : ".");
            String sourceId = alert.tenantId() + ":" + alert.monthStart();
            PlatformTenantAuditor.asTenant(alert.tenantSchema(), SYSTEM_ACTOR, () -> issueService.record(
                IssueSource.SPEND_CAP, sourceId,
                alert.stage() >= 100 ? IssueSeverity.ERROR : IssueSeverity.WARNING, title, message));
        } catch (RuntimeException e) {
            log.warn("Spend-cap {}% platform issue could not be recorded for tenant {}", alert.stage(),
                alert.tenantId(), e);
        }
    }
}
