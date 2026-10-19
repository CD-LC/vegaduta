package com.agenticai.core.agents.job.schedule;

import com.agenticai.core.agents.job.schedule.AgentScheduleService.DueSchedule;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The once-a-minute tick that turns {@code agent_schedule} rows into agent jobs.
 *
 * <p>Structurally identical to {@code WorkflowScheduler}, on purpose - the two
 * are the same kind of thing and should fail the same way. That means:
 * {@link PgAdvisoryJobLock#tryRunExclusive} so two replicas do not both tick,
 * {@link TenantSchemaIterator#forEachActiveTenantSchema} because a
 * {@code @Scheduled} method has no {@code TenantContext} and an un-iterated
 * repository call would silently only ever see the empty {@code public} schema
 * (the bug that class exists to prevent), and a per-schedule try/catch so one
 * bad row cannot stop the rest of a tenant's schedules firing in the same tick.
 *
 * <p>The advisory lock is a de-duplication convenience and NEVER the guarantee -
 * it is documented fail-open. Correctness comes from
 * {@link AgentScheduleRepository#claimOccurrence}'s compare-and-set and from
 * V208's two unique indexes on {@code agent_job}.
 *
 * <p><b>The tick only ENQUEUES.</b> No LLM call, no blocking work, nothing that
 * takes longer than a few queries. {@code spring.task.scheduling.pool.size} is 8
 * and about twenty-one {@code @Scheduled} methods already share it; whatever the
 * agent actually does happens afterwards on {@code AgentJobExecutor}'s own
 * bounded pool.
 *
 * <p>{@link #TICK_MS} is a constant rather than a property, matching {@code
 * WorkflowScheduler} exactly. A minute of granularity is an accepted trade for a
 * background trigger, and a configurable interval is one more knob that can be
 * set to a value that starves the shared scheduling pool.
 */
@Component
public class AgentScheduleTicker {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduleTicker.class);
    private static final long TICK_MS = 60_000;
    private static final String JOB_LOCK_NAME = "agent-schedule-ticker";

    private final TenantSchemaIterator tenantSchemaIterator;
    private final AgentScheduleService scheduleService;
    private final PgAdvisoryJobLock jobLock;

    public AgentScheduleTicker(
        TenantSchemaIterator tenantSchemaIterator,
        AgentScheduleService scheduleService,
        PgAdvisoryJobLock jobLock
    ) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.scheduleService = scheduleService;
        this.jobLock = jobLock;
    }

    @Scheduled(fixedRate = TICK_MS)
    public void fireDueSchedules() {
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::fireDueSchedulesExclusively);
    }

    private void fireDueSchedulesExclusively() {
        // ONE `now` for the whole tick rather than a fresh Instant.now() per
        // schedule: the same instant decides which schedules are due and computes
        // each one's next slot, so a slow tick cannot leave a schedule that was
        // due at the top of the tick looking not-yet-due by the time it is
        // reached, and re-appear next minute as a duplicate.
        Instant now = Instant.now();
        tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
            List<DueSchedule> due = scheduleService.findDue(now);
            for (DueSchedule schedule : due) {
                try {
                    scheduleService.fireOrSkip(schedule, now);
                } catch (RuntimeException e) {
                    // Per-schedule, not left to TenantSchemaIterator's per-tenant
                    // catch: one broken schedule in a tenant that has several must
                    // not stop that tenant's others from firing in this same tick.
                    log.error(
                        "Unexpected error firing agent schedule {} (agent {}) in tenant schema '{}'",
                        schedule.scheduleId(), schedule.agentId(), schema, e);
                }
            }
        });
    }
}
