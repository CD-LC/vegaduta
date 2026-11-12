package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memorystore.MemoryAgentAccess;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link MemoryAgentAccess} over {@link AgentService#requireAccess}: the memory-item REST surface
 * in agents-memory enforces exactly the VIEWER/EDITOR bar of {@code AgentMemoryController}. The
 * adapter lives here, not in agents-memory, because this package already depends on {@code
 * agents.memorystore} (the WP4c adapters) and the reverse edge would close a package cycle.
 */
@Component
public class MemoryItemAgentAccessAdapter implements MemoryAgentAccess {

    private final AgentService agentService;

    public MemoryItemAgentAccessAdapter(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public void requireViewer(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
    }

    @Override
    public void requireEditor(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
    }

    @Override
    public boolean isEditor(UUID agentId) {
        try {
            agentService.requireAccess(agentId, PermissionLevel.EDITOR);
            return true;
        } catch (ResourceAccessDeniedException e) {
            return false;
        }
    }
}
