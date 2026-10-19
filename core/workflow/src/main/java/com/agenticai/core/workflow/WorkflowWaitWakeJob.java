package com.agenticai.core.workflow;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes parked Wait nodes whose time has come. This is the durable timer: it runs on the existing {@code
 * @Scheduled} infrastructure (no new thread pool, nothing in memory), so a restart loses no wait - the {@code
 * workflow_wait} rows are the schedule. Same shape as {@link WorkflowRunRecoveryJob}: iterate tenants with {@link
 * TenantSchemaIterator} (a repository query without it would only see the empty {@code public} schema) and take
 * a {@link PgAdvisoryJobLock} so one replica sweeps per tick (the claim inside {@link WorkflowWaitResumeService}
 * makes a lock-less double sweep harmless, just wasteful).
 *
 * <p>The whole job is a no-op while {@code app.workflow.wait-node.enabled} is false: with the flag off no Wait
 * node can validate or run, so there is nothing to wake.
 */
@Component
public class WorkflowWaitWakeJob {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWaitWakeJob.class);
    private static final String JOB_LOCK_KEY = "workflow-wait-wake";

    private final TenantSchemaIterator schemaIterator;
    private final WorkflowWaitResumeService resumeService;
    private final ObjectProvider<PgAdvisoryJobLock> jobLock;
    private final boolean enabled;

    public WorkflowWaitWakeJob(
        TenantSchemaIterator schemaIterator,
        WorkflowWaitResumeService resumeService,
        ObjectProvider<PgAdvisoryJobLock> jobLock,
        @Value("${app.workflow.wait-node.enabled:false}") boolean enabled
    ) {
        this.schemaIterator = schemaIterator;
        this.resumeService = resumeService;
        this.jobLock = jobLock;
        this.enabled = enabled;
    }

    @Scheduled(
        fixedDelayString = "${app.workflow.wait-node.sweep-interval-ms:30000}",
        initialDelayString = "${app.workflow.wait-node.sweep-initial-delay-ms:60000}"
    )
    public void wakeDueWaits() {
        if (!enabled) {
            return;
        }
        PgAdvisoryJobLock lock = jobLock.getIfAvailable();
        if (lock != null) {
            lock.tryRunExclusive(JOB_LOCK_KEY, this::sweepAllTenants);
        } else {
            sweepAllTenants();
        }
    }

    /** Package-private seam so tests exercise the sweep without the lock wrapper. */
    void sweepAllTenants() {
        schemaIterator.forEachActiveTenantSchema(schema -> {
            int handled = resumeService.sweepDue(Instant.now());
            if (handled > 0) {
                log.info("Woke {} waiting workflow run(s) in schema '{}'.", handled, schema);
            }
        });
    }
}
