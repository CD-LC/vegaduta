package com.agenticai.core.agents.job.trigger;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link AgentEventTriggerFire}, the per-event claim ledger.
 *
 * <p>No finder by {@code (triggerId, eventKey)} on purpose. Reading before
 * inserting would be exactly the check-then-insert race the unique index exists
 * to close, and having the method available is how somebody eventually
 * "optimises" the insert away. The only supported way to claim an event is
 * {@code saveAndFlush} and letting {@code DataIntegrityViolationException}
 * decide - see {@link AgentEventTriggerFiring}.
 *
 * <p>The other two queries here are the loop guard's, and they read the LEDGER
 * rather than a counter column on the trigger. That is the fix for a real
 * lost-update defect: firings run concurrently (an {@code @Async} publisher,
 * concurrent webhook threads), the trigger entity has no {@code @Version}, and
 * two firings that both read {@code N} and both write {@code N + 1} lose a count
 * exactly when the firing rate is highest. A row per fire cannot be lost.
 */
public interface AgentEventTriggerFireRepository extends JpaRepository<AgentEventTriggerFire, UUID> {

    /**
     * A SLIDING count of one trigger's firings since {@code from}, used for both
     * the burst window and the sustained window (see {@link
     * AgentEventTriggerFiring}). Sliding rather than tumbling because a tumbling
     * window resets on elapsed time and therefore can never see a loop slower
     * than the window - which is the shape the motivating document-generation
     * cycle actually has.
     *
     * <p>Backed by V210's {@code idx_agent_event_trigger_fire_window}.
     */
    long countByTriggerIdAndFiredAtGreaterThanEqual(UUID triggerId, Instant from);

    /**
     * The TENANT-WIDE sliding count: every trigger's firings since {@code
     * from}. One tenant is one schema, so counting the whole table IS the
     * per-tenant count - no tenant column exists or is needed. This is what
     * backs {@code max-tenant-fires-per-window}/{@code
     * ...-per-sustained-window}, the only bound a FAN-OUT (many triggers, each
     * inside its own per-trigger budget) answers to.
     *
     * <p>Backed by V210's {@code idx_agent_event_trigger_fire_age} (fired_at) -
     * originally added for the future retention sweep, doing real work already.
     */
    long countByFiredAtGreaterThanEqual(Instant from);

    /**
     * The duplicate-delivery guard for event types whose {@code runId} is NOT
     * stable across re-publication - which is eight of the nine subscribable
     * types, because {@code WorkflowEventPublisher#publishNewRun} mints a fresh
     * random UUID per call. The exact {@code (trigger_id, event_key)} unique
     * index cannot recognise those, so this fingerprint check is what stops a
     * provider retry from buying a second billed job.
     *
     * <p>Backed by V210's {@code idx_agent_event_trigger_fire_fingerprint}.
     */
    boolean existsByTriggerIdAndPayloadFingerprintAndFiredAtGreaterThanEqual(
        UUID triggerId, String payloadFingerprint, Instant from);
}
