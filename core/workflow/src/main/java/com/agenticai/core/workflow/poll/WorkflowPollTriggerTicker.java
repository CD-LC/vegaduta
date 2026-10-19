package com.agenticai.core.workflow.poll;

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
 * Fires due HTTP-poll triggers (V482) - the polling counterpart of {@code
 * WorkflowScheduler}'s cron schedules. Same shape on purpose: one tick,
 * {@link PgAdvisoryJobLock} so exactly one core replica polls per tick on a
 * multi-replica deployment (the lock fails open to at-most-once-per-replica,
 * and the seen-id ring keeps even that safe), then {@link
 * TenantSchemaIterator} to visit every ACTIVE tenant's own
 * {@code workflow_poll_trigger} table with {@code TenantContext} set
 * correctly per tenant.
 *
 * <p>The tick rate is {@code app.workflow.poll.tick} (default 60 s) - a poll
 * can therefore land up to one tick late, the same stated trade-off the
 * scheduler makes. Sub-tick cadences are rejected at configure time
 * ({@code min-interval-seconds} defaults to 60), not silently rounded.
 */
@Component
public class WorkflowPollTriggerTicker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPollTriggerTicker.class);
    private static final String JOB_LOCK_NAME = "workflow-poll-trigger";

    private final TenantSchemaIterator tenantSchemaIterator;
    private final WorkflowPollTriggerService pollService;
    private final PgAdvisoryJobLock jobLock;
    private final WorkflowPollProperties properties;

    public WorkflowPollTriggerTicker(
        TenantSchemaIterator tenantSchemaIterator,
        WorkflowPollTriggerService pollService,
        PgAdvisoryJobLock jobLock,
        WorkflowPollProperties properties
    ) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.pollService = pollService;
        this.jobLock = jobLock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.workflow.poll.tick:60s}")
    public void runDuePollTriggers() {
        if (!properties.isEnabled()) {
            return;
        }
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::runDuePollTriggersExclusively);
    }

    private void runDuePollTriggersExclusively() {
        Instant now = Instant.now();
        tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
            for (UUID id : pollService.findDue(now)) {
                // Per-trigger catch, not the iterator's per-tenant catch: one
                // broken poll must not stop this tenant's other due triggers
                // in the same tick.
                try {
                    pollService.runDue(id);
                } catch (RuntimeException e) {
                    log.error("Unexpected error running poll trigger {} in tenant schema '{}'", id, schema, e);
                }
            }
        });
    }
}
