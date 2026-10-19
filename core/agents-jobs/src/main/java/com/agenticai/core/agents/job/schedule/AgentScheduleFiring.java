package com.agenticai.core.agents.job.schedule;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobLauncher;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobView;
import com.agenticai.core.agents.job.schedule.AgentScheduleService.AgentScheduleView;
import com.agenticai.core.agents.job.schedule.AgentScheduleService.DueSchedule;
import com.agenticai.core.agents.job.schedule.AgentScheduleService.FiringDecision;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

/**
 * The firing half of {@link AgentScheduleService}: executes one already-claimed occurrence through
 * the overlap rule ({@link AgentScheduleService#decide}) and records the run row. Split out of
 * that class to keep it readable; not a bean - one instance is owned by the service. Logs under
 * the service's logger so log routing is unchanged by the split.
 */
final class AgentScheduleFiring {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduleService.class);

    private final AgentScheduleRepository repository;
    private final AgentRepository agentRepository;
    private final AgentJobLauncher launcher;
    private final AgentJobProperties properties;
    private final MeterRegistry meterRegistry;
    private final AgentScheduleRunRepository runRepository;
    private final ObjectProvider<ScheduledWorkflowRunner> workflowRunner;

    AgentScheduleFiring(
        AgentScheduleRepository repository,
        AgentRepository agentRepository,
        AgentJobLauncher launcher,
        AgentJobProperties properties,
        MeterRegistry meterRegistry,
        AgentScheduleRunRepository runRepository,
        ObjectProvider<ScheduledWorkflowRunner> workflowRunner
    ) {
        this.repository = repository;
        this.agentRepository = agentRepository;
        this.launcher = launcher;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.runRepository = runRepository;
        this.workflowRunner = workflowRunner;
    }

    /**
     * What one attempt to fire produced: a skip reason (with the blocking job when
     * there was one) or the recorded run row. Both null means "not ours to fire".
     */
    record FireResult(
        AgentScheduleSkipReason skipReason, Optional<AgentJobView> live, AgentScheduleRun run
    ) {
        static FireResult skip(AgentScheduleSkipReason reason, Optional<AgentJobView> live) {
            return new FireResult(reason, live, null);
        }

        static FireResult fired(AgentScheduleRun run) {
            return new FireResult(null, Optional.empty(), run);
        }

        static FireResult notClaimed() {
            return new FireResult(null, Optional.empty(), null);
        }
    }

    /**
     * Decides and executes one firing for an already-claimed occurrence. Shared by
     * the tick and run-now so both go through identical guards. Only ever
     * ENQUEUES: an agent job onto the job executor, or a workflow run onto the
     * runner's own bounded pool.
     */
    FireResult fire(DueSchedule due, Instant now, String triggerKind) {
        Optional<Agent> agent = agentRepository.findById(due.agentId());
        AgentScheduleView view = new AgentScheduleView(
            due.scheduleId(),
            due.agentId(),
            due.timezone(),
            due.goalTemplate(),
            true,
            due.observedNextRunAt(),
            due.lastFiredAt(),
            agent.map(Agent::getName).orElse(null),
            isUsable(due, agent),
            due.sentinel()
        );

        if (due.targetType() == AgentScheduleTarget.WORKFLOW) {
            return fireWorkflow(due, view, now, triggerKind);
        }

        Optional<AgentJobView> live = launcher.findLiveForSchedule(due.scheduleId());
        FiringDecision decision = AgentScheduleService.decide(view, live, now);

        if (decision instanceof FiringDecision.Skip skip) {
            return FireResult.skip(skip.reason(), live);
        }
        if (!(decision instanceof FiringDecision.Fire fire)) {
            return FireResult.notClaimed();
        }

        try {
            UUID jobId = launcher.enqueue(new AgentJobLaunch(
                due.agentId(),
                fire.goal(),
                AgentJobOrigin.SCHEDULE,
                // origin_ref carries the cadence, so "who asked for this job?" is
                // answerable from the job row alone even after the schedule is
                // deleted or re-configured.
                due.cadence().describe(),
                due.scheduleId(),
                fire.occurrenceAt(),
                due.createdBy(),
                // No roles snapshot exists on the schedule row, so a scheduled
                // job runs with NONE. That is the safe direction and a deliberate
                // one: fewer roles can only ever make the job less capable than
                // the human who configured it, never more, which is the invariant
                // AgentJob#getRequestedByRoleSet's javadoc states for the same
                // reason. A stored role snapshot would instead let a schedule
                // keep exercising an authority its owner has since lost.
                Set.of(),
                due.createdBy(),
                null
            ));
            recordFire(due, jobId, now);
            AgentScheduleRun run = saveRun(AgentScheduleRun.create(
                UUID.randomUUID(), due.scheduleId(), AgentScheduleTarget.AGENT, triggerKind,
                AgentScheduleRun.ENQUEUED, now, jobId, null, null));
            return FireResult.fired(run);
        } catch (DataIntegrityViolationException lostRace) {
            // V208's unique index rejected our insert: another replica already
            // created this occurrence (or a live job for this schedule still
            // exists and our overlap read missed it by microseconds). The guard
            // of record worked. Recorded as a skip so the counters stay honest.
            log.info(
                "Agent schedule {} lost a concurrent firing race for occurrence {} - the unique index rejected the "
                    + "duplicate job, which is the guard working, not a failure",
                due.scheduleId(), due.observedNextRunAt());
            return FireResult.skip(AgentScheduleSkipReason.CONCURRENT_FIRING_LOST, Optional.empty());
        }
    }

    /**
     * WORKFLOW-target firing. Same shape as the agent path (skip reasons, never
     * blocks the tick) but the overlap marker is a recent RUNNING row in {@code
     * agent_schedule_run}, since a workflow run is not an {@code agent_job}.
     * A RUNNING row older than {@code blocked-alert-after} is treated as a crashed
     * runner (its completion callback never fired) and does not block.
     */
    private FireResult fireWorkflow(DueSchedule due, AgentScheduleView view, Instant now, String triggerKind) {
        ScheduledWorkflowRunner runner = workflowRunner.getIfAvailable();
        if (!view.agentUsable()) {
            return FireResult.skip(AgentScheduleSkipReason.AGENT_UNAVAILABLE, Optional.empty());
        }
        if (runner == null || due.workflowId() == null || !runner.exists(due.workflowId())) {
            return FireResult.skip(AgentScheduleSkipReason.WORKFLOW_UNAVAILABLE, Optional.empty());
        }
        Instant staleBefore = now.minus(properties.getSchedule().getBlockedAlertAfter());
        boolean inFlight = runRepository
            .findFirstByScheduleIdAndStatusAndStartedAtAfterOrderByStartedAtDesc(
                due.scheduleId(), AgentScheduleRun.RUNNING, staleBefore)
            .isPresent();
        if (inFlight) {
            return FireResult.skip(AgentScheduleSkipReason.PREVIOUS_RUN_RUNNING, Optional.empty());
        }
        Instant occurrenceAt = due.observedNextRunAt() == null ? now : due.observedNextRunAt();
        String input = AgentScheduleService.renderGoal(view, occurrenceAt);
        UUID workflowRunId = UUID.randomUUID();
        // Row first, so the runner's completion callback always has something to update.
        AgentScheduleRun row = saveRun(AgentScheduleRun.create(
            UUID.randomUUID(), due.scheduleId(), AgentScheduleTarget.WORKFLOW, triggerKind,
            AgentScheduleRun.RUNNING, now, null, workflowRunId, null));
        try {
            runner.submit(due.workflowId(), workflowRunId, input, due.createdBy(),
                (status, error) -> completeRun(row.getId(), status, error));
        } catch (RejectedExecutionException busy) {
            row.finish(AgentScheduleRun.FAILED, "Workflow runner is busy; run was not started", now);
            runRepository.save(row);
            return FireResult.skip(AgentScheduleSkipReason.WORKFLOW_BUSY, Optional.empty());
        }
        recordFire(due, workflowRunId, now);
        return FireResult.fired(row);
    }

    AgentScheduleRun saveRun(AgentScheduleRun run) {
        AgentScheduleRun saved = runRepository.save(run);
        try {
            // Prune: everything past the newest RUN_HISTORY_LIMIT rows. Best-effort -
            // history housekeeping must never fail a firing.
            List<AgentScheduleRun> excess = runRepository.findByScheduleIdOrderByStartedAtDesc(
                run.getScheduleId(), PageRequest.of(1, AgentScheduleService.RUN_HISTORY_LIMIT));
            if (!excess.isEmpty()) {
                runRepository.deleteAll(excess);
            }
        } catch (RuntimeException e) {
            log.warn("Could not prune run history for agent schedule {}", run.getScheduleId(), e);
        }
        return saved;
    }

    /**
     * Completion callback for WORKFLOW runs, invoked by the runner on its worker
     * thread (tenant context already restored).
     */
    void completeRun(UUID runRowId, String status, String error) {
        try {
            runRepository.findById(runRowId).ifPresent(run -> {
                run.finish(status, error, Instant.now());
                runRepository.save(run);
            });
        } catch (RuntimeException e) {
            log.warn("Could not record completion of scheduled workflow run {}", runRowId, e);
        }
    }

    /**
     * A schedule can only fire if its agent still exists, is not SUPERVISED, and
     * the row still names the human whose authority the run will carry. The last
     * of those is folded in here rather than given its own skip reason because
     * the operator-visible consequence is identical - this schedule cannot
     * produce a job until somebody re-saves it - and the log line below is what
     * distinguishes the three cases for whoever is actually diagnosing it.
     */
    private boolean isUsable(DueSchedule due, Optional<Agent> agent) {
        if (agent.isEmpty()) {
            log.warn("Agent schedule {} points at agent {}, which no longer exists", due.scheduleId(), due.agentId());
            return false;
        }
        if (due.targetType() == AgentScheduleTarget.AGENT
            && agent.get().getAutonomyTier() == AgentAutonomyTier.SUPERVISED) {
            log.warn(
                "Agent schedule {} cannot fire: agent {} was moved to SUPERVISED after the schedule was created, so an "
                    + "unattended run would park on its first tool call",
                due.scheduleId(), due.agentId());
            return false;
        }
        if (due.createdBy() == null || due.createdBy().isBlank()) {
            log.warn(
                "Agent schedule {} has no created_by, so there is no human identity to run or bill the job against; "
                    + "re-save the schedule to attach one",
                due.scheduleId());
            return false;
        }
        return true;
    }

    private void recordFire(DueSchedule due, UUID jobId, Instant now) {
        repository.findById(due.scheduleId()).ifPresent(schedule -> {
            schedule.fired(jobId, now);
            repository.save(schedule);
        });
        meterRegistry.counter("agent.schedule.fired").increment();
        log.info("Agent schedule {} fired job {} for agent {}", due.scheduleId(), jobId, due.agentId());
    }
}
