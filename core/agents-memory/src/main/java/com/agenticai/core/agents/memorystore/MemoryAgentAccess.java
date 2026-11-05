package com.agenticai.core.agents.memorystore;

import java.util.UUID;

/**
 * Agent-scoped authorization for the WP6 REST surface: the same VIEWER/EDITOR bar every other
 * {@code /api/agents/{agentId}/...} controller enforces through {@code AgentService.requireAccess}.
 *
 * <p>This is a port, not a direct call to {@code AgentService}: the kernel's {@code agents.core}
 * package must not appear in this module's dependencies, because {@code agents.memory} in
 * agents-turn already depends on this package (the WP4c embedder/screen adapters) and the edge
 * {@code agents.memorystore -> agents.core} would close a package cycle that {@code
 * StructureRulesTest} fails on. The adapter implementation lives in agents-turn.
 */
public interface MemoryAgentAccess {

    /**
     * Throws {@code ResourceAccessDeniedException} (a 404) unless the caller may view the agent.
     */
    void requireViewer(UUID agentId);

    /**
     * Throws {@code ResourceAccessDeniedException} (a 404) unless the caller may edit the agent.
     */
    void requireEditor(UUID agentId);

    /** EDITOR-or-higher check that answers false instead of throwing on denial. */
    boolean isEditor(UUID agentId);
}
