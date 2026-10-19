package com.agenticai.core.mcpserver.dto;

import java.util.Set;
import java.util.UUID;

public record McpServerAccessGrantUpdateRequest(
    Set<UUID> exposedAgentIds,
    Set<UUID> exposedWorkflowIds,
    Set<UUID> exposedKnowledgeCollectionIds
) {
}
