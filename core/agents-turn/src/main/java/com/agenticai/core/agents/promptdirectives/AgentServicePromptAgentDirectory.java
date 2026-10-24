package com.agenticai.core.agents.promptdirectives;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.prompts.PromptAgentDirectory;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells the prompt registry whether an agent exists in the calling thread's tenant (the agent table
 * is in the tenant's schema, so an id from another tenant is simply absent).
 */
@Component
class AgentServicePromptAgentDirectory implements PromptAgentDirectory {

    private final AgentService agents;

    AgentServicePromptAgentDirectory(AgentService agents) {
        this.agents = agents;
    }

    @Override
    public boolean existsInCurrentTenant(UUID agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            agents.requireExists(agentId);
            return true;
        } catch (AgentNotFoundException e) {
            return false;
        }
    }

    /** VIEWER access for the calling user (owner, share grant or tenant admin); no access, or no such agent, is false. */
    @Override
    public boolean canViewInCurrentTenant(UUID agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            agents.requireAccess(agentId, PermissionLevel.VIEWER);
            return true;
        } catch (AgentNotFoundException | ResourceAccessDeniedException e) {
            return false;
        }
    }
}
