package com.agenticai.core.agents.scheduledtask;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A recurring prompt: "every weekday at 8am summarise my unread email and send me the top 5". See {@code
 * db/migration/tenant/V451__scheduled_task.sql} for the column-by-column rationale (and why this is a sibling of
 * {@code agent_schedule} rather than a change to it).
 *
 * <p><b>This entity never executes anything.</b> It holds configuration and counters; every firing goes through
 * {@code AgentJobLauncher#enqueue} and lands in {@code agent_job}. The mutators are named for the OUTCOME they
 * record rather than being setters, because each has to keep two or three counters consistent - {@code
 * consecutive_failures} in particular is only meaningful if every success resets it and every FINAL failure (one
 * that was not retried) increments it, and a bare setter is how a call site eventually forgets.
 */
@Entity
@Table(name = "scheduled_task")
public class ScheduledTask {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_kind", nullable = false, length = 12)
    private ScheduledTaskSchedule.Kind scheduleKind;

    @Column(name = "schedule_time", length = 5)
    private String scheduleTime;

    @Column(name = "schedule_day", length = 8)
    private String scheduleDay;

    @Column(name = "cron", nullable = false, length = 64)
    private String cron;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery", nullable = false, length = 12)
    private ScheduledTaskDelivery delivery = ScheduledTaskDelivery.IN_APP;

    @Column(name = "delivery_to", length = 255)
    private String deliveryTo;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "disabled_reason", length = 255)
    private String disabledReason;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_status", length = 16)
    private String lastStatus;

    @Column(name = "last_job_id")
    private UUID lastJobId;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "run_count", nullable = false)
    private long runCount;

    @Column(name = "max_runs")
    private Integer maxRuns;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "owner_subject", nullable = false, length = 200)
    private String ownerSubject;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScheduledTask() {
        // JPA
    }

    /**
     * @param owner the human whose authority every firing runs with - frozen here and threaded into the job's
     *     {@code requestedBy}/{@code subjectKey}, so a task can never run as a "system" identity no person has
     *     nor spend against a budget nobody owns.
     */
    public static ScheduledTask create(
        UUID id, UUID agentId, String name, String prompt, ScheduledTaskSchedule.Compiled schedule,
        ScheduledTaskDelivery delivery, String deliveryTo, Integer maxRuns, Instant expiresAt,
        String owner, Instant nextRunAt, Instant now
    ) {
        ScheduledTask task = new ScheduledTask();
        task.id = id;
        task.agentId = agentId;
        task.ownerSubject = owner;
        task.createdAt = now;
        task.enabled = true;
        task.applyConfiguration(name, prompt, schedule, delivery, deliveryTo, maxRuns, expiresAt);
        task.nextRunAt = nextRunAt;
        task.updatedAt = now;
        return task;
    }

    private void applyConfiguration(
        String name, String prompt, ScheduledTaskSchedule.Compiled schedule,
        ScheduledTaskDelivery delivery, String deliveryTo, Integer maxRuns, Instant expiresAt
    ) {
        this.name = name;
        this.prompt = prompt;
        this.scheduleKind = schedule.kind();
        this.scheduleTime = schedule.time();
        this.scheduleDay = schedule.day();
        this.cron = schedule.cron();
        this.timezone = schedule.timezone();
        this.delivery = delivery;
        this.deliveryTo = delivery.isExternal() ? deliveryTo : null;
        this.maxRuns = maxRuns;
        this.expiresAt = expiresAt;
    }

    /**
     * Re-configures the task. The owner is NOT changed (a task keeps the authority of the human who created it).
     * A paused task stays paused - editing is not a way to resume - but the failure streak is cleared: the person
     * has just responded to whatever the failures were telling them.
     */
    public void reconfigure(
        String name, String prompt, ScheduledTaskSchedule.Compiled schedule,
        ScheduledTaskDelivery delivery, String deliveryTo, Integer maxRuns, Instant expiresAt,
        Instant nextRunAt, Instant now
    ) {
        applyConfiguration(name, prompt, schedule, delivery, deliveryTo, maxRuns, expiresAt);
        this.consecutiveFailures = 0;
        this.nextRunAt = this.enabled ? nextRunAt : null;
        this.updatedAt = now;
    }

    /** A user pause. Keeps every setting so resume is one click; nulls the tick's claim target. */
    public void pause(Instant now) {
        this.enabled = false;
        this.disabledReason = null;
        this.nextRunAt = null;
        this.updatedAt = now;
    }

    /** Re-enables with a freshly computed slot, clearing any system pause reason and the failure streak. */
    public void resume(Instant nextRunAt, Instant now) {
        this.enabled = true;
        this.disabledReason = null;
        this.consecutiveFailures = 0;
        this.nextRunAt = nextRunAt;
        this.updatedAt = now;
    }

    /** The SYSTEM pausing the task (auto-disable, expiry, run cap, unparseable cron). */
    public void autoDisable(String reason, Instant now) {
        this.enabled = false;
        this.disabledReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 255));
        this.nextRunAt = null;
        this.updatedAt = now;
    }

    /**
     * A job was enqueued for this task.
     *
     * @param countsAsRun {@code false} for the single automatic retry: a retry re-does a run that already counted,
     *     so it must not eat the task's {@code max_runs} budget
     * @return {@code true} when this run used the last of the {@code max_runs} budget and the task paused itself
     */
    public boolean recordStarted(UUID jobId, Instant now, boolean countsAsRun) {
        this.lastRunAt = now;
        this.lastJobId = jobId;
        this.lastStatus = STATUS_RUNNING;
        this.updatedAt = now;
        if (!countsAsRun) {
            return false;
        }
        this.runCount = this.runCount + 1;
        if (runCapReached() && this.enabled) {
            autoDisable("Finished: it reached its limit of " + this.maxRuns + " runs.", now);
            return true;
        }
        return false;
    }

    /** A run-now failure: shown as the last status but neither retried nor counted toward the auto-pause streak. */
    public void recordFailureWithoutStreak(Instant now) {
        this.lastStatus = STATUS_FAILED;
        this.updatedAt = now;
    }

    /** A firing succeeded: resets the failure streak. */
    public void recordSuccess(Instant now) {
        this.lastStatus = STATUS_SUCCEEDED;
        this.consecutiveFailures = 0;
        this.updatedAt = now;
    }

    /** The user (or an operator) cancelled the run: neither a success nor a failure for the streak. */
    public void recordCancelled(Instant now) {
        this.lastStatus = STATUS_CANCELLED;
        this.updatedAt = now;
    }

    /** A firing was skipped (previous run still live, missed window): no effect on the streak. */
    public void recordSkipped(Instant now) {
        this.lastStatus = STATUS_SKIPPED;
        this.updatedAt = now;
    }

    /**
     * A FINAL failure - one that will not be retried. Increments the streak and, when it reaches {@code
     * maxConsecutiveFailures}, pauses the task.
     *
     * @return {@code true} when this failure auto-disabled the task (the caller then notifies the owner)
     */
    public boolean recordFinalFailure(int maxConsecutiveFailures, Instant now) {
        this.lastStatus = STATUS_FAILED;
        this.consecutiveFailures = this.consecutiveFailures + 1;
        this.updatedAt = now;
        if (this.consecutiveFailures >= maxConsecutiveFailures && this.enabled) {
            autoDisable("Paused automatically after " + this.consecutiveFailures + " failed runs in a row.", now);
            return true;
        }
        return false;
    }

    /** True when {@code max_runs} is set and has been reached. */
    public boolean runCapReached() {
        return maxRuns != null && runCount >= maxRuns;
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getName() {
        return name;
    }

    public String getPrompt() {
        return prompt;
    }

    public ScheduledTaskSchedule.Kind getScheduleKind() {
        return scheduleKind;
    }

    public String getScheduleTime() {
        return scheduleTime;
    }

    public String getScheduleDay() {
        return scheduleDay;
    }

    public String getCron() {
        return cron;
    }

    public String getTimezone() {
        return timezone;
    }

    public ScheduledTaskDelivery getDelivery() {
        return delivery;
    }

    public String getDeliveryTo() {
        return deliveryTo;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public UUID getLastJobId() {
        return lastJobId;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public long getRunCount() {
        return runCount;
    }

    public Integer getMaxRuns() {
        return maxRuns;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
