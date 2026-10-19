package com.agenticai.core.workflow;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedule trigger (#166) - "how do customers trigger it" without a human
 * present at all: a tenant picks a frequency (hourly/daily/weekly - see
 * WorkflowCanvas.js's preset picker, translated to a plain cron string
 * before it reaches {@code WorkflowService#enableSchedule}), and this class
 * fires it automatically, forever, with no external caller required (unlike
 * the webhook trigger, which needs something outside this platform to make
 * the call).
 *
 * <p>Ticks once a minute and, via {@link TenantSchemaIterator}, checks every
 * ACTIVE tenant's own {@code workflow_definition} table for schedules whose
 * {@code next_run_at} has passed - the same "iterate every tenant schema,
 * one at a time, with TenantContext set correctly for each" pattern every
 * other tenant-scoped background job in this codebase uses (see that
 * class's own javadoc for why a scheduled job cannot just query a
 * repository directly). A one-minute tick means a schedule can fire up to
 * ~60 seconds later than its exact configured time - an accepted, stated
 * trade-off for a background job, not a real-time guarantee.
 *
 * <p>Multi-replica safe since the {@link PgAdvisoryJobLock} wrap: each tick
 * runs on exactly one {@code core} replica (the one that wins the advisory
 * lock); the others skip and try again next minute. This closes the
 * double-run hazard this javadoc used to state as an accepted
 * single-instance-only assumption. The lock fails open (see that class's
 * javadoc), so a lock outage degrades back to the old at-most-double-run
 * behavior rather than stopping schedules entirely.
 */
@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);
    private static final long TICK_MS = 60_000;
    private static final String JOB_LOCK_NAME = "workflow-scheduler";

    private final TenantSchemaIterator tenantSchemaIterator;
    private final WorkflowService workflowService;
    private final PgAdvisoryJobLock jobLock;

    public WorkflowScheduler(
        TenantSchemaIterator tenantSchemaIterator,
        WorkflowService workflowService,
        PgAdvisoryJobLock jobLock
    ) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.workflowService = workflowService;
        this.jobLock = jobLock;
    }

    @Scheduled(fixedRate = TICK_MS)
    public void runDueSchedules() {
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::runDueSchedulesExclusively);
    }

    private void runDueSchedulesExclusively() {
        Instant now = Instant.now();
        tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
            List<UUID> due = workflowService.findDueScheduledWorkflowIds(now);
            for (UUID workflowId : due) {
                log.info("Firing scheduled workflow {} in tenant schema '{}'", workflowId, schema);
                // Deliberately caught per-workflow, not left to
                // TenantSchemaIterator's own per-tenant catch: one broken
                // scheduled workflow in a tenant with several should not
                // stop that tenant's other due workflows from firing in the
                // same tick.
                try {
                    workflowService.runScheduledWorkflow(workflowId);
                } catch (RuntimeException e) {
                    log.error("Unexpected error running scheduled workflow {} in tenant schema '{}'", workflowId, schema, e);
                }
            }
        });
    }
}
