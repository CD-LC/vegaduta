package com.agenticai.core.mcpserver.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Never carries the raw API key or its hash - only {@link McpServerAccessGrantCreatedResponse} (creation/rotation) ever includes the raw key, exactly once. */
public record McpServerAccessGrantResponse(
    UUID id,
    String name,
    String apiKeyDisplayPrefix,
    boolean enabled,
    Set<UUID> exposedAgentIds,
    Set<UUID> exposedWorkflowIds,
    Set<UUID> exposedKnowledgeCollectionIds,
    Instant createdAt,
    Instant updatedAt,
    Instant lastUsedAt
) {
}
