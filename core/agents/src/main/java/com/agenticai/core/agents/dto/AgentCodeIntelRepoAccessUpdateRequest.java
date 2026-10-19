package com.agenticai.core.agents.dto;

import java.util.Set;

/**
 * Migration V103 - opt-in per-agent repo scope for the code-intel
 * write/execute tools. Unlike {@link AgentKnowledgeAccessUpdateRequest},
 * empty/null {@code allowedCodeIntelRepoIds} means DENY-ALL, not
 * "unrestricted" - see {@code Agent.allowedCodeIntelRepoIds}' javadoc for
 * why. Only matters once the {@code code-workspace} capability is on (see
 * {@link com.agenticai.core.agents.capability.CodeWorkspaceCapability} and
 * {@code PATCH /api/agents/{id}/capabilities/code-workspace}).
 */
public record AgentCodeIntelRepoAccessUpdateRequest(Set<String> allowedCodeIntelRepoIds) {
}
