package com.agenticai.core.common.improve;

import java.time.Instant;
import java.util.UUID;

/**
 * Response shapes for {@link AgentImprovementController} (JAVA-016). The
 * controller used to return the JPA entities themselves, which couples the
 * wire format to the table and serializes whatever getter an entity grows
 * next. Field names match the entities' previous JSON exactly, so the wire
 * contract is unchanged.
 */
public final class ImprovementDtos {

    private ImprovementDtos() {
    }

    public record SuggestionResponse(
        UUID id,
        String tenantId,
        UUID agentId,
        UUID issueId,
        ImprovementKind kind,
        String title,
        String rationale,
        String suggestedText,
        ImprovementStatus status,
        String appliedRef,
        Instant createdAt,
        Instant decidedAt,
        String decidedBy
    ) {
        public static SuggestionResponse from(AgentImprovementSuggestion s) {
            return new SuggestionResponse(
                s.getId(), s.getTenantId(), s.getAgentId(), s.getIssueId(), s.getKind(), s.getTitle(),
                s.getRationale(), s.getSuggestedText(), s.getStatus(), s.getAppliedRef(), s.getCreatedAt(),
                s.getDecidedAt(), s.getDecidedBy());
        }
    }

    public record HealthScoreResponse(
        UUID id,
        String tenantId,
        UUID agentId,
        int score,
        AgentHealthStatus status,
        int openIssueCount,
        int pendingSuggestionCount,
        Instant lastSweptAt,
        Instant updatedAt
    ) {
        public static HealthScoreResponse from(AgentHealthScore h) {
            return new HealthScoreResponse(
                h.getId(), h.getTenantId(), h.getAgentId(), h.getScore(), h.getStatus(), h.getOpenIssueCount(),
                h.getPendingSuggestionCount(), h.getLastSweptAt(), h.getUpdatedAt());
        }
    }
}
