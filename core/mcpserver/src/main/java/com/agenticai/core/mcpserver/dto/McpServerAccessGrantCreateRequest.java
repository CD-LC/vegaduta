package com.agenticai.core.mcpserver.dto;

import java.util.Set;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;

/**
 * @param exposedAgentIds required opt-in set of this tenant's own agent ids to expose to
 *     whoever holds the generated key - never defaulted to "all agents" (AgentToolProvider's
 *     established per-agent opt-in principle, applied here at the grant level).
 * @param exposedWorkflowIds accepted, persisted, and enforced by McpCallWorkflowToolCallback -
 *     see McpServerAccessGrant's javadoc.
 * @param exposedKnowledgeCollectionIds accepted, persisted, and enforced by
 *     McpSearchKnowledgeToolCallback.
 */
public record McpServerAccessGrantCreateRequest(
    @NotBlank String name,
    Set<UUID> exposedAgentIds,
    Set<UUID> exposedWorkflowIds,
    Set<UUID> exposedKnowledgeCollectionIds
) {
}
