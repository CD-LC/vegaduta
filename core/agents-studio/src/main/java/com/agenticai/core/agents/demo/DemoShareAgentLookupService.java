package com.agenticai.core.agents.demo;

import com.agenticai.core.agents.core.AgentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only agent lookups for the public share surface, so {@link DemoShareController} never
 * touches {@link AgentRepository} directly. The caller is responsible for being inside the demo
 * tenant context.
 */
@Service
public class DemoShareAgentLookupService {

    private final AgentRepository agentRepository;

    public DemoShareAgentLookupService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Transactional(readOnly = true)
    public Optional<String> findAgentName(UUID agentId) {
        return agentRepository.findById(agentId).map(a -> a.getName());
    }
}
