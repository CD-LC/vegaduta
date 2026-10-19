package com.agenticai.core.agents.job.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A cron trigger that turns one agent into a job PRODUCER - see {@code
 * db/migration/tenant/V208__agent_schedule.sql} for the column-by-column
 * rationale (why this is its own table, why the cron is stored normalized, why
 * {@code last_job_id} is a display pointer and never the overlap guard).
 *
 * <p><b>This entity never executes anything.</b> It holds configuration and
 * counters; every firing goes through {@code AgentJobLauncher#enqueue} and lands
 * in {@code agent_job}. There is exactly one place agent work runs, and adding
 * schedules did not add a second.
 *
 * <p>The mutators below are named for the OUTCOME they record ({@link #fired},
 * {@link #skipped}, {@link #claimOccurrence}) rather than being setters, because
 * each one has to keep two or three counters consistent - {@code
 * consecutive_skip_count} in particular is only meaningful if EVERY fire resets
 * it, and a bare setter is how one of the three call sites eventually forgets.
 */
@Entity
@Table(name = "agent_schedule")
public class AgentSchedule {

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** Null for an interval schedule; exactly one of cron / intervalSeconds is set (V338). */
    @Column(name = "cron", length = 64)
    private String cron;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private AgentScheduleTarget targetType = AgentScheduleTarget.AGENT;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "goal_template", nullable = false, columnDefinition = "TEXT")
    private String goalTemplate;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(name = "last_job_id")
    private UUID lastJobId;

    @Column(name = "last_skipped_at")
    private Instant lastSkippedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_skip_reason", length = 48)
    private AgentScheduleSkipReason lastSkipReason;

    @Column(name = "consecutive_skip_count", nullable = false)
    private int consecutiveSkipCount;

    @Column(name = "total_fire_count", nullable = false)
    private long totalFireCount;

    @Column(name = "total_skip_count", nullable = false)
    private long totalSkipCount;

    /**
     * Sentinel mode (V413): the cadence is a watch brief, not a task. Each
     * firing's goal carries the {@code SentinelVerdict} contract (a {@code
     * ```sentinel} block), and {@code AgentSentinelSweep} turns the verdict
     * into a notification or a parked proposal instead of treating the run
     * like a deliverable. Only meaningful for AGENT targets - the upsert
     * rejects {@code sentinel && targetType == WORKFLOW} because a workflow
     * run has no result text for a verdict to live in.
     */
    @Column(name = "sentinel_enabled", nullable = false)
    private boolean sentinelEnabled;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentSchedule() {
        // JPA
    }

    /**
     * @param createdBy the human whose authority every firing will run with.
     *     Frozen here and threaded into {@code AgentJobLaunch#requestedBy}/
     *     {@code #subjectKey} at every enqueue - a schedule must never become a
     *     way to run an agent as a "system" identity no person has, nor to spend
     *     against a budget nobody owns.
     */
    public static AgentSchedule create(
        UUID id, UUID agentId, AgentScheduleCadence cadence, String timezone, String goalTemplate,
        AgentScheduleTarget targetType, UUID workflowId,
        String createdBy, Instant nextRunAt, Instant now, boolean sentinelEnabled
    ) {
        AgentSchedule schedule = new AgentSchedule();
        schedule.id = id;
        schedule.agentId = agentId;
        schedule.cron = cadence.cron();
        schedule.intervalSeconds = cadence.intervalSeconds();
        schedule.targetType = targetType;
        schedule.workflowId = workflowId;
        schedule.timezone = timezone;
        schedule.goalTemplate = goalTemplate;
        schedule.enabled = true;
        schedule.nextRunAt = nextRunAt;
        schedule.sentinelEnabled = sentinelEnabled;
        schedule.createdBy = createdBy;
        schedule.createdAt = now;
        schedule.updatedAt = now;
        return schedule;
    }

    /**
     * Re-configures an existing schedule (the PUT is an upsert, matching the
     * workflow schedule API's shape).
     *
     * <p>Deliberately RESETS {@code consecutiveSkipCount} and clears the last
     * skip: an operator who has just changed the cadence or the goal has
     * responded to whatever the skips were telling them, and carrying the old
     * streak forward would re-fire the "this schedule keeps skipping"
     * notification immediately on a schedule that has not skipped since. The
     * lifetime totals are NOT reset - those are history, not a signal.
     *
     * <p>{@code createdBy} is likewise NOT changed. A schedule keeps the
     * authority of the human who first configured it; letting an edit silently
     * re-point the identity would mean any editor could escalate a schedule to
     * their own (possibly broader) permissions by touching the goal text.
     */
    public void reconfigure(
        AgentScheduleCadence cadence, String timezone, String goalTemplate,
        AgentScheduleTarget targetType, UUID workflowId, Instant nextRunAt, Instant now,
        boolean sentinelEnabled
    ) {
        this.cron = cadence.cron();
        this.intervalSeconds = cadence.intervalSeconds();
        this.targetType = targetType;
        this.workflowId = workflowId;
        this.timezone = timezone;
        this.goalTemplate = goalTemplate;
        this.sentinelEnabled = sentinelEnabled;
        this.enabled = true;
        this.nextRunAt = nextRunAt;
        this.consecutiveSkipCount = 0;
        this.lastSkipReason = null;
        this.lastSkippedAt = null;
        this.updatedAt = now;
    }

    /**
     * Re-enables a paused schedule with its stored configuration; the caller
     * supplies the freshly computed next slot.
     */
    public void resume(Instant nextRunAt, Instant now) {
        this.enabled = true;
        this.nextRunAt = nextRunAt;
        this.consecutiveSkipCount = 0;
        this.lastSkipReason = null;
        this.lastSkippedAt = null;
        this.updatedAt = now;
    }

    public AgentScheduleCadence cadence() {
        return AgentScheduleCadence.stored(cron, intervalSeconds);
    }

    /**
     * Turns the trigger off while KEEPING {@code cron} and {@code goalTemplate},
     * so re-enabling is one click and does not require the operator to remember
     * what they had configured. {@code nextRunAt} is nulled because it is the
     * tick's claim target: a disabled row with a due timestamp is a row the
     * "find due" query would keep returning.
     */
    public void disable(Instant now) {
        this.enabled = false;
        this.nextRunAt = null;
        this.updatedAt = now;
    }

    /**
     * Records a firing. Resets the consecutive-skip streak - that counter means
     * "how many firings in a ROW were lost", so one success must clear it or the
     * threshold notification eventually fires on a perfectly healthy schedule.
     */
    public void fired(UUID jobId, Instant firedAt) {
        this.lastFiredAt = firedAt;
        this.lastJobId = jobId;
        this.consecutiveSkipCount = 0;
        this.lastSkipReason = null;
        this.totalFireCount = this.totalFireCount + 1;
        this.updatedAt = firedAt;
    }

    /**
     * Records a skip. Note this does NOT touch {@code nextRunAt}: the tick has
     * already advanced it as part of claiming the occurrence, which is precisely
     * what makes a blocked firing a SKIP rather than a retry that piles up behind
     * the blocking job.
     */
    public void skipped(AgentScheduleSkipReason reason, Instant skippedAt) {
        this.lastSkippedAt = skippedAt;
        this.lastSkipReason = reason;
        this.consecutiveSkipCount = this.consecutiveSkipCount + 1;
        this.totalSkipCount = this.totalSkipCount + 1;
        this.updatedAt = skippedAt;
    }

    /**
     * In-memory mirror of {@link AgentScheduleRepository#claimOccurrence}'s
     * conditional UPDATE, for the entity copy the tick is about to keep working
     * with. The authoritative advance is that statement, not this method - only
     * one replica's UPDATE matches, and this just keeps the loaded copy honest.
     */
    public void claimOccurrence(Instant next, Instant now) {
        this.nextRunAt = next;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }

    public AgentScheduleTarget getTargetType() {
        return targetType;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getCron() {
        return cron;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getGoalTemplate() {
        return goalTemplate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isSentinelEnabled() {
        return sentinelEnabled;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getLastFiredAt() {
        return lastFiredAt;
    }

    public UUID getLastJobId() {
        return lastJobId;
    }

    public Instant getLastSkippedAt() {
        return lastSkippedAt;
    }

    public AgentScheduleSkipReason getLastSkipReason() {
        return lastSkipReason;
    }

    public int getConsecutiveSkipCount() {
        return consecutiveSkipCount;
    }

    public long getTotalFireCount() {
        return totalFireCount;
    }

    public long getTotalSkipCount() {
        return totalSkipCount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
