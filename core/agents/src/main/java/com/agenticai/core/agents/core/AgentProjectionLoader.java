package com.agenticai.core.agents.core;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads an {@link AgentProjection} in one read-only transaction. A separate bean so the transaction
 * proxy applies when {@link AgentProjectionCache} calls it (a self-call inside {@code AgentService}
 * would not go through the proxy), and so a cache HIT opens no transaction at all.
 */
@Service
class AgentProjectionLoader {

    private final AgentRepository repository;

    AgentProjectionLoader(AgentRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    AgentProjection load(UUID agentId) {
        Agent agent =
                repository.findById(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
        return AgentProjection.from(agent, repository::findById);
    }
}
