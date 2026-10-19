package com.agenticai.core.quality.access;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code agent.created_by} by primary key for {@link QualityAgentAccessGate}.
 * Native + unqualified for the same reason as {@code AgentDefinitionLookup}: this
 * module cannot reference the {@code Agent} entity, and the unqualified table
 * resolves against the caller's tenant schema, so an agent id from another tenant
 * is simply "not found" here.
 */
@Component
public class AgentOwnerLookup {

    /** The agent exists; {@code createdBy} is null for legacy rows. */
    public record AgentOwner(String createdBy) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<AgentOwner> find(UUID agentId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager
            .createNativeQuery("SELECT created_by FROM agent WHERE id = CAST(:id AS uuid)")
            .setParameter("id", agentId.toString())
            .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object first = rows.get(0);
        return Optional.of(new AgentOwner(first == null ? null : first.toString()));
    }
}
