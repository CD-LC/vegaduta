package com.agenticai.core.agents.openai;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Seam that lets a long-lived API-key caller (an MCP/developer grant key, {@code vmcp_...},
 * authenticated by core/mcpserver's filter before the request reaches {@link OpenAiCompatController})
 * be confined to the agents its grant exposes. core/mcpserver depends on core/agents, so the
 * controller cannot reference the grant classes directly; mcpserver provides the implementation.
 *
 * <p>An empty result means "this request was NOT authenticated by a grant key" (a JWT caller),
 * which keeps the normal per-user {@code requireAccess(RUNNER)} behaviour.
 */
public interface OpenAiCompatCallerScope {

    /** The agent ids the current request's grant exposes, or empty when the caller is not a grant-key caller. */
    Optional<Set<UUID>> exposedAgentIds();
}
