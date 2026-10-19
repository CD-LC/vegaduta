package com.agenticai.core.agents.scheduledtask;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The once-a-minute ticks that turn {@code scheduled_task} rows into agent jobs and finished jobs into delivered
 * results. Structurally identical to {@code AgentScheduleTicker} on purpose - the same lock, the same tenant
 * iteration, the same "only ENQUEUE, never block" rule (the shared 8-thread scheduling pool is already crowded).
 *
 * <p><b>Multi-replica safe, in layers.</b> {@link PgAdvisoryJobLock#tryRunExclusive} makes exactly one core
 * instance run a tick - but it is documented FAIL-OPEN, so it is a de-duplication convenience only. Correctness
 * comes from the compare-and-set claims in {@code ScheduledTaskRepository#claimOccurrence} and {@code
 * ScheduledTaskRunRepository#claimForSettlement} and from V208's unique indexes on {@code agent_job}.
 *
 * <p><b>Flag off = identity.</b> With {@code app.scheduled-tasks.enabled=false} (the default) both ticks return
 * before touching the database or the tenant iterator, so deploying this code changes nothing at runtime.
 */
@Component
public class ScheduledTaskTicker {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskTicker.class);
    private static final String FIRE_LOCK = "scheduled-task-ticker";
    private static final String SETTLE_LOCK = "scheduled-task-settle";

    private final TenantSchemaIterator tenantSchemaIterator;
    private final ScheduledTaskRunner runner;
    private final PgAdvisoryJobLock jobLock;
    private final ScheduledTaskSettings settings;

    public ScheduledTaskTicker(
        TenantSchemaIterator tenantSchemaIterator,
        ScheduledTaskRunner runner,
        PgAdvisoryJobLock jobLock,
        ScheduledTaskSettings settings
    ) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.runner = runner;
        this.jobLock = jobLock;
        this.settings = settings;
    }

    /** Fires due tasks. A minute of granularity is an accepted trade for a background trigger. */
    @Scheduled(fixedRate = 60_000, initialDelay = 50_000)
    public void fireDueTasks() {
        if (!settings.isEnabled()) {
            return;
        }
        jobLock.tryRunExclusive(FIRE_LOCK, () -> {
            // ONE `now` for the whole tick so a slow tick cannot leave a task that was due at the top of it
            // looking not-yet-due by the time it is reached (and re-appear next minute as a duplicate).
            Instant now = Instant.now();
            tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
                try {
                    runner.fireDueTasks(now);
                } catch (RuntimeException e) {
                    log.error("Scheduled-task tick failed in tenant schema '{}'", schema, e);
                }
            });
        });
    }

    /** Settles runs whose job ended: delivers the result, records cost and status, retries / auto-pauses. */
    @Scheduled(fixedRate = 60_000, initialDelay = 80_000)
    public void settleFinishedRuns() {
        if (!settings.isEnabled()) {
            return;
        }
        jobLock.tryRunExclusive(SETTLE_LOCK, () -> {
            Instant now = Instant.now();
            tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
                try {
                    runner.settleFinishedRuns(now);
                } catch (RuntimeException e) {
                    log.error("Scheduled-task settle sweep failed in tenant schema '{}'", schema, e);
                }
            });
        });
    }
}
