package com.agenticai.core.agents.job.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One firing of an {@link AgentSchedule} (see V338). History only - never read by
 * the overlap guard for AGENT targets (that queries {@code agent_job}); for
 * WORKFLOW targets a recent RUNNING row is the in-flight marker.
 */
@Entity
@Table(name = "agent_schedule_run")
public class AgentScheduleRun {

    public static final String ENQUEUED = "ENQUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    public static final String TRIGGER_MANUAL = "MANUAL";

    @Id
    private UUID id;

    @Column(name = "schedule_id", nullable = false)
    private UUID scheduleId;

    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    @Column(name = "trigger_kind", nullable = false, length = 16)
    private String triggerKind;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "workflow_run_id")
    private UUID workflowRunId;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /**
     * Sentinel-mode verdict bookkeeping (V413): NULL until {@code
     * AgentSentinelSweep} has judged this firing's finished job, then one of
     * the {@code SentinelOutcome} strings (QUIET/NOTIFIED/PROPOSED/
     * NO_VERDICT/JOB_FAILED/JOB_GONE). The IS NULL filter is what makes the
     * sweep exactly-once across replicas and restarts.
     */
    @Column(name = "sentinel_outcome", length = 24)
    private String sentinelOutcome;

    /**
     * Audit context for {@link #sentinelOutcome} - the parked approval request
     * id for PROPOSED, the dead job's terminal state for JOB_FAILED, the
     * verdict's own title otherwise. Narrow on purpose: the notification the
     * outcome produced already carries the full human-readable body.
     */
    @Column(name = "sentinel_outcome_note", length = 500)
    private String sentinelOutcomeNote;

    protected AgentScheduleRun() {
        // JPA
    }

    public static AgentScheduleRun create(
        UUID id, UUID scheduleId, AgentScheduleTarget target, String triggerKind, String status,
        Instant startedAt, UUID jobId, UUID workflowRunId, String error
    ) {
        AgentScheduleRun run = new AgentScheduleRun();
        run.id = id;
        run.scheduleId = scheduleId;
        run.targetType = target.name();
        run.triggerKind = triggerKind;
        run.status = status;
        run.startedAt = startedAt;
        run.jobId = jobId;
        run.workflowRunId = workflowRunId;
        run.error = error == null ? null : truncate(error);
        boolean terminal = SUCCEEDED.equals(status) || FAILED.equals(status) || SKIPPED.equals(status);
        run.finishedAt = terminal ? startedAt : null;
        return run;
    }

    public void finish(String status, String error, Instant at) {
        this.status = status;
        this.error = error == null ? null : truncate(error);
        this.finishedAt = at;
    }

    /** Written once by the sentinel sweep; a second write is a bug, not an update. */
    public void recordSentinelOutcome(String outcome, String note) {
        this.sentinelOutcome = outcome;
        this.sentinelOutcomeNote = note == null || note.length() <= 500 ? note : note.substring(0, 500);
    }

    private static String truncate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    public UUID getId() {
        return id;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public String getSentinelOutcome() {
        return sentinelOutcome;
    }

    public String getSentinelOutcomeNote() {
        return sentinelOutcomeNote;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTriggerKind() {
        return triggerKind;
    }

    public String getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public String getError() {
        return error;
    }
}
