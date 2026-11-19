package com.agenticai.core.agents.core;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Small read-only agent lookups (names, counts) for other feature areas, so they never touch
 * {@link AgentRepository}.
 */
@Service
public class AgentLookupService {

    private final AgentRepository agentRepository;

    public AgentLookupService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Transactional(readOnly = true)
    public Optional<String> nameOf(UUID agentId) {
        return agentRepository.findById(agentId).map(Agent::getName);
    }

    /** Names keyed by agent id; a null name is reported as "agent". */
    @Transactional(readOnly = true)
    public Map<UUID, String> namesByIds(Collection<UUID> agentIds) {
        return agentRepository.findAllById(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, a -> a.getName() == null ? "agent" : a.getName()));
    }

    @Transactional(readOnly = true)
    public long count() {
        return agentRepository.count();
    }
}
