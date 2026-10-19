package com.agenticai.core.eventbus;

import java.time.Instant;
import java.util.Map;

/**
 * Interactive Workflow Designer PRD, Section 9 - the envelope every event on
 * the {@code workflow.events} Kafka topic uses, whether it is Phase 0's
 * simple "a trigger fired" proof-of-pipe event or (from Phase 1 onward) a
 * real node-started/node-completed/approval-requested event emitted by the
 * WorkflowExecutionEngine.
 *
 * <p>Deliberately a plain record with a {@code Map<String, Object>} payload
 * rather than a sealed hierarchy of typed event classes - the set of event
 * types will grow steadily through every phase in the PRD, and a new event
 * type should never require a schema-registry-style coordinated change here.
 * A JSON Schema / Avro-backed envelope is called out in the PRD as a later
 * hardening step (Section 9) if event volume and cross-service consumers
 * ever justify it - not a Phase 0 requirement.
 *
 * @param eventType  e.g. {@code "channel.whatsapp.message.received"} - see
 *                    {@link WorkflowEventTypes} for the constants defined so
 *                    far. Free-form on purpose (see class javadoc).
 * @param tenantId    the tenant's schema name - same identifier {@link
 *                    com.agenticai.core.security.TenantContext} already uses
 *                    everywhere else in this codebase.
 * @param workflowId  null in Phase 0 (no {@code Workflow} entity exists yet);
 *                    populated from Phase 1 onward.
 * @param runId       correlates every event belonging to one execution
 *                    together, and is also the Kafka partition key (prefixed
 *                    with {@code tenantId}) - see {@link WorkflowEventPublisher}.
 * @param nodeId      null for run-level events (e.g. the initial trigger);
 *                    populated once a node actually starts.
 * @param timestamp   when this event was created, not when it was published
 *                    (those can legitimately differ under outbox-relay lag).
 * @param version     envelope schema version - 1 for everything emitted so
 *                    far. Bump this, not the event type, if this record's
 *                    shape itself ever needs a breaking change.
 * @param payload     event-type-specific data.
 */
public record WorkflowEvent(
    String eventType,
    String tenantId,
    String workflowId,
    String runId,
    String nodeId,
    Instant timestamp,
    int version,
    Map<String, Object> payload
) {

    public static final int CURRENT_VERSION = 1;

    public static WorkflowEvent of(
        String eventType, String tenantId, String runId, Map<String, Object> payload
    ) {
        return new WorkflowEvent(eventType, tenantId, null, runId, null, Instant.now(), CURRENT_VERSION, payload);
    }

    /** The Kafka message key - see {@link WorkflowEventPublisher}'s javadoc for why this shape. */
    public String partitionKey() {
        return tenantId + ":" + runId;
    }
}
