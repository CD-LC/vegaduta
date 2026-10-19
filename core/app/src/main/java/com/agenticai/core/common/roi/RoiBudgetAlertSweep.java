package com.agenticai.core.common.roi;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every ten minutes, raises the 80% / 100% monthly-budget alerts for each active tenant (see {@link
 * RoiBudgetService#raiseThresholdAlerts}). Polling rather than hooking the LLM call path keeps the
 * chat hot path untouched; the alert can therefore trail the crossing by up to one tick.
 */
@Component
public class RoiBudgetAlertSweep {

    private static final Logger log = LoggerFactory.getLogger(RoiBudgetAlertSweep.class);
    private static final String JOB_LOCK_NAME = "roi-budget-alert-sweep";

    private final TenantSchemaIterator tenants;
    private final PgAdvisoryJobLock jobLock;
    private final RoiBudgetService budget;
    private final boolean enabled;

    public RoiBudgetAlertSweep(
            TenantSchemaIterator tenants,
            PgAdvisoryJobLock jobLock,
            RoiBudgetService budget,
            @Value("${roi.budget-alerts.enabled:true}") boolean enabled) {
        this.tenants = tenants;
        this.jobLock = jobLock;
        this.budget = budget;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "UTC")
    public void sweep() {
        if (!enabled) {
            return;
        }
        jobLock.tryRunExclusive(
                JOB_LOCK_NAME,
                () ->
                        tenants.forEachActiveTenantSchema(
                                schema -> {
                                    try {
                                        int raised = budget.raiseThresholdAlerts();
                                        if (raised > 0) {
                                            log.info(
                                                    "Raised {} budget alert(s) in tenant schema '{}'",
                                                    raised,
                                                    schema);
                                        }
                                    } catch (RuntimeException e) {
                                        log.error(
                                                "Budget alert sweep failed in tenant schema '{}'",
                                                schema,
                                                e);
                                    }
                                }));
    }
}
