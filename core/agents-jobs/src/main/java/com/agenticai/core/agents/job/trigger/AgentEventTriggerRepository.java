package com.agenticai.core.agents.job.trigger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link AgentEventTrigger}.
 *
 * <p>Ordinary CRUD, with one query that is not: {@link
 * #findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc} runs on a WEBHOOK
 * REQUEST THREAD for every published event, so it is deliberately an index seek
 * against V210's partial {@code idx_agent_event_trigger_dispatch}, bounded at
 * the SQL level rather than trusting the configurable cap alone. A tenant that
 * somehow accumulated thousands of rows must not be able to make one inbound
 * WhatsApp message allocate an unbounded list on the thread Twilio is waiting on.
 */
public interface AgentEventTriggerRepository extends JpaRepository<AgentEventTrigger, UUID> {

    /**
     * The dispatcher's only read. {@code Top50} is the hard ceiling;
     * {@code app.agent-jobs.trigger.max-triggers-per-event} (10) is applied on
     * top of it in {@link AgentEventTriggerDispatcher} and is the number an
     * operator can actually tune. Two bounds on purpose: the configurable one
     * can be raised by an operator, the SQL one cannot.
     */
    List<AgentEventTrigger> findTop50ByEventTypeAndEnabledTrueOrderByCreatedAtAsc(String eventType);

    /** The upsert's read - V210's {@code uq_agent_event_trigger_agent_event} makes this a single row, not a list. */
    Optional<AgentEventTrigger> findByAgentIdAndEventType(UUID agentId, String eventType);

    List<AgentEventTrigger> findByAgentIdOrderByEventTypeAsc(UUID agentId);

    /**
     * Enforces {@code app.agent-jobs.trigger.max-triggers-per-agent} on the write
     * path. Counts ENABLED rows only, so a disabled trigger costs an agent
     * nothing.
     */
    long countByAgentIdAndEnabledTrue(UUID agentId);

    /**
     * Records that a firing produced a job, as ONE atomic SQL statement rather
     * than a read-modify-write on the loaded entity.
     *
     * <p>Firings really do run concurrently - document generation publishes from
     * an {@code @Async} thread and channel webhooks arrive on concurrent request
     * threads - and this entity carries no {@code @Version}. Incrementing in
     * memory and calling {@code save} would lose counts under exactly the load
     * that makes them interesting. Same idiom, same reason, as {@code
     * AgentJobRepository}'s {@code j.parkCount = j.parkCount + 1}.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentEventTrigger t set t.fireCount = t.fireCount + 1, t.lastFiredAt = :now, "
        + "t.updatedAt = :now where t.id = :id")
    int recordFired(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Turns a trigger off, GUARDED on it still being on so two firings that trip
     * the guard in the same instant produce one disable and one rowcount 0 -
     * which is also how the caller knows whether it is the one that must raise
     * the diagnostic.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AgentEventTrigger t set t.enabled = false, t.disabledReason = :reason, t.updatedAt = :now "
        + "where t.id = :id and t.enabled = true")
    int disableIfEnabled(
        @Param("id") UUID id,
        @Param("reason") AgentEventTriggerDisableReason reason,
        @Param("now") Instant now
    );
}
