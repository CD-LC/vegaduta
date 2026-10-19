package com.agenticai.core.agents.core;

import com.agenticai.core.security.HotpathCaches;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.stereotype.Component;

/** Evicts an agent's cached {@link AgentProjection} on ANY write to the {@code agent} row. */
@Component
class AgentProjectionEviction extends WriteEviction {

    private static final long serialVersionUID = 1L;

    AgentProjectionEviction(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    boolean watches(Object entity) {
        return entity instanceof Agent;
    }

    @Override
    void evict(String tenantId, Object entity) {
        Agent agent = (Agent) entity;
        HotpathCaches.evictAgent(tenantId, agent.getId() == null ? null : agent.getId().toString());
    }
}
