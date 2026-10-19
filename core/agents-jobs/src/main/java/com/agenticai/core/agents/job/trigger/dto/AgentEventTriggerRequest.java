package com.agenticai.core.agents.job.trigger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/agents/{agentId}/triggers}. The PUT is an upsert keyed
 * on {@code (agentId, eventType)} - one subscription per agent per event type,
 * so there is no create-vs-update distinction for the caller to get wrong,
 * matching the agent schedule API's shape.
 *
 * @param eventType one of the NINE event types the platform actually publishes
 *     (see {@code AgentTriggerEventTypes}). Anything else is a 400 listing the
 *     valid set - event names are not invented here, and a subscription to an
 *     event nothing emits is a trigger that silently never fires.
 * @param filterJson optional flat JSON object of {@code field -> expected
 *     value}, ALL of which must match for the trigger to fire, e.g.
 *     <code>{"channel":"whatsapp"}</code>. Keys are validated against the fields
 *     that event type actually carries. Deliberately not an expression language
 *     - see {@code AgentEventTriggerFilter}.
 * @param goalTemplate what the agent is asked to do on each firing. Supports
 *     <code>{{eventType}}</code>, <code>{{runId}}</code>,
 *     <code>{{occurredAt}}</code> and <code>{{payload.&lt;field&gt;}}</code> for
 *     the fields that event type carries; an unknown placeholder is a 400 at
 *     configuration time rather than a surprise at 3am. Rendered at ENQUEUE, so
 *     the goal stored on the job is frozen and auditable even after the template
 *     is edited - and payload values are interpolated as DATA, never as
 *     instructions (see {@code AgentEventTriggerGoal}).
 */
public record AgentEventTriggerRequest(
    @NotBlank @Size(max = 128) String eventType,
    @Size(max = 4000) String filterJson,
    @NotBlank @Size(max = 4000) String goalTemplate
) {
}
