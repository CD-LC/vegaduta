package com.agenticai.core.agents.dto;

import java.util.Set;
import java.util.UUID;

/**
 * Update the agent's collaborator list. Empty/null
 * {@code collaboratorAgentIds} clears every delegation permission (back to "no collaborators") -
 * matches {@code Agent.collaboratorAgentIds}' semantics exactly, same shape as
 * {@link AgentKnowledgeAccessUpdateRequest}.
 */
public record AgentCollaboratorsUpdateRequest(Set<UUID> collaboratorAgentIds) {
}
