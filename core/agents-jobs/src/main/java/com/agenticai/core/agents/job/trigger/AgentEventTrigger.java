package com.agenticai.core.agents.job.trigger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A subscription that turns one agent into a job PRODUCER driven by platform
 * events - see {@code db/migration/tenant/V210__agent_event_trigger.sql} for the
 * column-by-column rationale (why the allow-list is enforced in code and not by
 * a CHECK, why the loop-guard counters are Postgres columns and not Redis, why
 * the firing ledger's {@code job_id} is nullable).
 *
 * <p><b>This entity never executes anything</b>, exactly like {@code
 * AgentSchedule}: it holds configuration and counters, and every firing goes
 * through {@code AgentJobLauncher#enqueue} into {@code agent_job}. There is one
 * place agent work runs and neither schedules nor triggers added a second.
 *
 * <p><b>This entity holds no firing counters, and that is the fix for a real
 * defect rather than a simplification.</b> The loop guard's counts are derived
 * from {@code agent_event_trigger_fire} at firing time (see {@link
 * AgentEventTriggerFiring}). A counter column here would be an unguarded
 * read-modify-write on an entity with no {@code @Version}, and firings really do
 * run concurrently - document generation publishes from an {@code @Async}
 * thread, channel webhooks arrive on concurrent request threads - so two
 * firings that both read {@code N} and both write {@code N + 1} would lose a
 * count, making the guard undercount exactly when the firing rate is highest.
 * {@code fire_count} and {@code last_fired_at} are likewise written by a single
 * atomic SQL UPDATE ({@code AgentEventTriggerRepository#recordFired}), never by
 * mutating this object.
 */
@Entity
@Table(name = "agent_event_trigger")
public class AgentEventTrigger {

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "filter_json", columnDefinition = "TEXT")
    private String filterJson;

    @Column(name = "goal_template", nullable = false, columnDefinition = "TEXT")
    private String goalTemplate;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "disabled_reason", length = 48)
    private AgentEventTriggerDisableReason disabledReason;

    /**
     * The loop guard's ONLY stored state. The counts themselves live in {@code
     * agent_event_trigger_fire} (one row per claimed event) and are counted at
     * firing time; this column is the line the counting starts from, so that
     * re-saving a trigger the guard disabled does not walk straight back into
     * the rows that disabled it.
     */
    @Column(name = "guard_reset_at")
    private Instant guardResetAt;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(name = "fire_count", nullable = false)
    private long fireCount;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentEventTrigger() {
        // JPA
    }

    /**
     * @param createdBy the human whose authority every firing will run with.
     *     Frozen here and threaded into {@code AgentJobLaunch#requestedBy}/
     *     {@code #subjectKey} at every enqueue - a trigger must never become a
     *     way to run an agent as a "system" identity no person has, nor to spend
     *     against a budget nobody owns. Identical rule, identical mechanism, to
     *     {@code AgentSchedule#create}.
     */
    public static AgentEventTrigger create(
        UUID id, UUID agentId, String eventType, String filterJson, String goalTemplate,
        String createdBy, Instant now
    ) {
        AgentEventTrigger trigger = new AgentEventTrigger();
        trigger.id = id;
        trigger.agentId = agentId;
        trigger.eventType = eventType;
        trigger.filterJson = filterJson;
        trigger.goalTemplate = goalTemplate;
        trigger.enabled = true;
        trigger.createdBy = createdBy;
        trigger.createdAt = now;
        trigger.updatedAt = now;
        return trigger;
    }

    /**
     * Re-configures an existing trigger (the PUT is an upsert, one row per
     * agent+event type).
     *
     * <p>Deliberately RE-ENABLES and CLEARS THE LOOP-GUARD WINDOW. This is the
     * only way back from {@link AgentEventTriggerDisableReason#LOOP_GUARD_TRIPPED},
     * and it has to reset the counters or a trigger the guard just disabled would
     * be re-disabled by the next single firing - the stale count is already at
     * the cap. An operator re-saving a trigger has responded to whatever the
     * guard was telling them; carrying the count forward would only make the
     * feature look broken. {@code fireCount}/{@code lastFiredAt} are NOT reset -
     * those are history, not a signal.
     *
     * <p>{@code createdBy} is likewise NOT changed. A trigger keeps the authority
     * of the human who first configured it; letting an edit silently re-point the
     * identity would mean any editor could escalate a trigger to their own
     * (possibly broader) permissions by touching the goal text.
     */
    public void reconfigure(String filterJson, String goalTemplate, Instant now) {
        this.filterJson = filterJson;
        this.goalTemplate = goalTemplate;
        this.enabled = true;
        this.disabledReason = null;
        this.guardResetAt = now;
        this.updatedAt = now;
    }

    /**
     * Turns the trigger off while KEEPING {@code eventType}, {@code filterJson}
     * and {@code goalTemplate}, so re-enabling is one PUT with the body the
     * operator already had rather than a re-authoring exercise.
     */
    public void disable(AgentEventTriggerDisableReason reason, Instant now) {
        this.enabled = false;
        this.disabledReason = reason;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getFilterJson() {
        return filterJson;
    }

    public String getGoalTemplate() {
        return goalTemplate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public AgentEventTriggerDisableReason getDisabledReason() {
        return disabledReason;
    }

    public Instant getGuardResetAt() {
        return guardResetAt;
    }

    public Instant getLastFiredAt() {
        return lastFiredAt;
    }

    public long getFireCount() {
        return fireCount;
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
