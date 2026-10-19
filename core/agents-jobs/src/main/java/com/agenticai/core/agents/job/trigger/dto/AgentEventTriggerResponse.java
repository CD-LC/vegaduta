package com.agenticai.core.agents.job.trigger.dto;

import com.agenticai.core.agents.job.trigger.AgentEventTrigger;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What {@code GET/PUT/DELETE /api/agents/{agentId}/triggers} returns.
 *
 * <p><b>{@code disabledReason} is the point of this DTO, not decoration.</b> The
 * one question an operator arrives with is "why is my agent not reacting?", and
 * the three interesting answers - somebody turned it off, the loop guard turned
 * it off, or it is on and simply has not matched anything - are otherwise
 * indistinguishable without reading a log.
 *
 * <p>There is deliberately no "fires in the current window" field. The loop
 * guard's counts are derived from the firing ledger at firing time rather than
 * stored on the trigger (a stored counter is a lost-update bug under concurrent
 * firings - see {@code AgentEventTrigger}), so serving one here would mean
 * running the guard's two counting queries on every list call. {@code
 * guardResetAt} is served instead: it is the line those counts start from, and
 * it is what an operator needs to confirm that re-saving a disabled trigger
 * actually cleared the guard.
 *
 * <p>{@code availablePlaceholders} is served rather than documented-only so the
 * console can render the goal-template help for whichever event type is selected
 * without shipping its own copy of the nine payload shapes - a copy that would
 * drift the first time a publisher adds a field.
 *
 * <p>Carries no secret of any kind, which is why {@code GET} is deliberately not
 * admin-gated in {@code SecurityConfig} while {@code PUT}/{@code DELETE} are -
 * the same read-open split the agent schedule API uses.
 */
public record AgentEventTriggerResponse(
    UUID id,
    String eventType,
    String filterJson,
    String goalTemplate,
    boolean enabled,
    String disabledReason,
    Instant guardResetAt,
    Instant lastFiredAt,
    long fireCount,
    Set<String> availablePlaceholders,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {

    public static AgentEventTriggerResponse from(AgentEventTrigger trigger, Set<String> availablePlaceholders) {
        return new AgentEventTriggerResponse(
            trigger.getId(),
            trigger.getEventType(),
            trigger.getFilterJson(),
            trigger.getGoalTemplate(),
            trigger.isEnabled(),
            trigger.getDisabledReason() == null ? null : trigger.getDisabledReason().name(),
            trigger.getGuardResetAt(),
            trigger.getLastFiredAt(),
            trigger.getFireCount(),
            availablePlaceholders,
            trigger.getCreatedBy(),
            trigger.getCreatedAt(),
            trigger.getUpdatedAt()
        );
    }
}
