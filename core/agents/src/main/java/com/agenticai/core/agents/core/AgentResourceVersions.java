package com.agenticai.core.agents.core;

import com.agenticai.core.security.http.ResourceVersionSource;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Conditional-request adapter for agents: the entity tag of {@code /api/agents/{agentId}} is the
 * agent's {@code updatedAt}, which every settings PATCH moves. A plain repository read, like {@link
 * AgentResourceOwnerLookup}; access is still enforced by the handler the request reaches.
 */
@Component
public class AgentResourceVersions implements ResourceVersionSource {

    private final AgentRepository repository;

    public AgentResourceVersions(AgentRepository repository) {
        this.repository = repository;
    }

    @Override
    public String collectionPath() {
        return "/api/agents";
    }

    @Override
    public Optional<Instant> lastModified(UUID id) {
        return repository.findById(id).map(Agent::getUpdatedAt);
    }
}
