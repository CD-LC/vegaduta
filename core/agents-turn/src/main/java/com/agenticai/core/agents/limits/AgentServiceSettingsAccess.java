package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.settings.SettingsAgentAccess;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells the settings API what the caller may do with an agent: VIEWER / EDITOR through {@link AgentService} (which also
 * answers 404 for an agent of another tenant, the agent table being in the tenant's schema, and 403 for a caller
 * without the grant).
 */
@Component
class AgentServiceSettingsAccess implements SettingsAgentAccess {

    private final AgentService agents;

    AgentServiceSettingsAccess(AgentService agents) {
        this.agents = agents;
    }

    @Override
    public void requireViewer(UUID agentId) {
        agents.requireAccess(agentId, PermissionLevel.VIEWER);
    }

    @Override
    public void requireEditor(UUID agentId) {
        agents.requireAccess(agentId, PermissionLevel.EDITOR);
    }

    @Override
    public void requireEditable(UUID agentId) {
        agents.assertEditable(agents.requireAccess(agentId, PermissionLevel.EDITOR));
    }
}
