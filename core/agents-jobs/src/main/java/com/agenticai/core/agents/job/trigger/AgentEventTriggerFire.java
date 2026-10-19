package com.agenticai.core.agents.job.trigger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per (trigger, event) that this trigger has already handled - the
 * CLAIM, not a log of what happened afterwards.
 *
 * <p><b>Two dedupe keys, because the platform supplies two very different
 * qualities of event identity.</b> The event pipe is at-least-once by
 * construction (a webhook provider retries, a poller can re-read the same
 * message), so the dedupe cannot be a check-then-insert in application code:
 * two deliveries on two request threads would both check, both miss, and both
 * enqueue.
 *
 * <ul>
 *   <li>{@code eventKey} = {@code eventType|runId}, under V210's {@code UNIQUE
 *       (trigger_id, event_key)}. The insert goes FIRST and the index is what
 *       makes the second delivery lose; the resulting {@code
 *       DataIntegrityViolationException} is read as "already handled" rather
 *       than as an error. Same posture, same reasoning, as V208's {@code
 *       uq_agent_job_schedule_occurrence}. <b>This is only a real guarantee when
 *       {@code runId} survives re-publication</b>, which of the nine
 *       subscribable types is true for {@code document.generation.completed}
 *       alone - the eight channel types mint a fresh UUID per delivery.</li>
 *   <li>{@code payloadFingerprint}, checked over {@code
 *       app.agent-jobs.trigger.duplicate-window} and NOT unique. This is what
 *       actually covers the channel types, and it is durable - unlike the
 *       upstream Redis guard, which is documented fail-open and is not called at
 *       all by {@code XDmPollingJob} or {@code EmailInboundPollingJob}.</li>
 * </ul>
 *
 * <p>{@code jobId} is therefore filled in AFTERWARDS and is legitimately null:
 * a claimed delivery that produced no job (the loop guard tripped on it, or the
 * trigger had no {@code created_by} to run as) still has its row, which is what
 * answers the operator's "the event arrived - where is my job?".
 *
 * <p>These rows are also the loop guard's COUNTER. Both sliding counts are
 * {@code count(*)} over this table for one trigger, which is what makes them
 * immune to the lost-update the first cut had when the count lived in a column
 * on the trigger itself.
 *
 * <p>{@code triggerId} is a plain UUID column rather than a {@code @ManyToOne},
 * matching {@code AgentSchedule#agentId}: the foreign key and its cascade live
 * in the DDL, and the dispatcher never wants the parent graph loaded on a
 * webhook request thread.
 */
@Entity
@Table(name = "agent_event_trigger_fire")
public class AgentEventTriggerFire {

    @Id
    private UUID id;

    @Column(name = "trigger_id", nullable = false)
    private UUID triggerId;

    @Column(name = "event_key", nullable = false, length = 255)
    private String eventKey;

    /**
     * SHA-256 over the event type and the canonicalised payload - the SECOND
     * dedupe key, and the one that actually covers channel events. Not unique:
     * it is checked over a time window ({@code
     * app.agent-jobs.trigger.duplicate-window}) rather than for all time,
     * because two genuinely distinct messages of the same length from the same
     * masked sender must be allowed to fire the trigger again eventually.
     */
    @Column(name = "payload_fingerprint", length = 64)
    private String payloadFingerprint;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt;

    protected AgentEventTriggerFire() {
        // JPA
    }

    public static AgentEventTriggerFire claim(
        UUID id, UUID triggerId, String eventKey, String payloadFingerprint, Instant firedAt
    ) {
        AgentEventTriggerFire fire = new AgentEventTriggerFire();
        fire.id = id;
        fire.triggerId = triggerId;
        fire.eventKey = eventKey;
        fire.payloadFingerprint = payloadFingerprint;
        fire.firedAt = firedAt;
        return fire;
    }

    public void recordJob(UUID jobId) {
        this.jobId = jobId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTriggerId() {
        return triggerId;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getPayloadFingerprint() {
        return payloadFingerprint;
    }

    public UUID getJobId() {
        return jobId;
    }

    public Instant getFiredAt() {
        return firedAt;
    }
}
