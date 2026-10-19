package com.agenticai.core.agents.scheduledtask;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One firing of a {@link ScheduledTask} (V451): history, cost and delivery outcome. The agent job it enqueued is
 * the source of truth for what the agent actually did; this row is what the console lists and what the settle
 * sweep walks (the {@code settled} flag is its exactly-once predicate).
 */
@Entity
@Table(name = "scheduled_task_run")
public class ScheduledTaskRun {

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_RETRY = "RETRY";

    public static final String STATUS_ENQUEUED = "ENQUEUED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SKIPPED = "SKIPPED";

    public static final String DELIVERY_IN_APP = "IN_APP";
    public static final String DELIVERY_DELIVERED = "DELIVERED";
    public static final String DELIVERY_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String DELIVERY_FAILED = "FAILED";

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "trigger_kind", nullable = false, length = 12)
    private String triggerKind;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "cost_usd", precision = 14, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "delivery_status", length = 20)
    private String deliveryStatus;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "settled", nullable = false)
    private boolean settled;

    protected ScheduledTaskRun() {
        // JPA
    }

    /** A run whose job is still in flight (unsettled). */
    public static ScheduledTaskRun enqueued(
        UUID id, UUID taskId, String triggerKind, int attempt, UUID jobId, Instant now
    ) {
        ScheduledTaskRun run = new ScheduledTaskRun();
        run.id = id;
        run.taskId = taskId;
        run.triggerKind = triggerKind;
        run.status = STATUS_ENQUEUED;
        run.attempt = attempt;
        run.startedAt = now;
        run.jobId = jobId;
        return run;
    }

    /** A run that ended without ever producing a job (skipped / refused / failed to enqueue): born settled. */
    public static ScheduledTaskRun ended(
        UUID id, UUID taskId, String triggerKind, int attempt, String status, String error, Instant now
    ) {
        ScheduledTaskRun run = new ScheduledTaskRun();
        run.id = id;
        run.taskId = taskId;
        run.triggerKind = triggerKind;
        run.status = status;
        run.attempt = attempt;
        run.startedAt = now;
        run.finishedAt = now;
        run.error = clip(error);
        run.settled = true;
        return run;
    }

    /** Called by the settle sweep once the job is terminal and the result has been (or failed to be) delivered. */
    public void settle(String status, String error, BigDecimal costUsd, String deliveryStatus, Instant now) {
        this.status = status;
        this.error = clip(error);
        this.costUsd = costUsd;
        this.deliveryStatus = deliveryStatus;
        this.finishedAt = now;
        this.settled = true;
    }

    private static String clip(String text) {
        return text == null ? null : text.substring(0, Math.min(text.length(), 2000));
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getTriggerKind() {
        return triggerKind;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
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

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public String getError() {
        return error;
    }

    public boolean isSettled() {
        return settled;
    }
}
