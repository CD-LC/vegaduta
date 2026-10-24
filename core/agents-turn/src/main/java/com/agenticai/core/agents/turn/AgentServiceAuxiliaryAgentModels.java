package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.auxiliary.AuxiliaryAgentModels;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reads an agent's provider and model through {@link AgentService} for the auxiliary-model resolver. */
@Component
public class AgentServiceAuxiliaryAgentModels implements AuxiliaryAgentModels {

    private final AgentService agents;

    public AgentServiceAuxiliaryAgentModels(AgentService agents) {
        this.agents = agents;
    }

    @Override
    public Optional<AgentModel> modelOf(UUID agentId) {
        Agent agent = agents.getEntity(agentId);
        return agent == null ? Optional.empty() : Optional.of(new AgentModel(agent.getProviderName(), agent.getModel()));
    }
}
