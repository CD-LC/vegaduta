package com.agenticai.core.agents.crew;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AgentSpecializationRepository extends JpaRepository<AgentSpecialization, UUID> {

    Optional<AgentSpecialization> findByAgentIdAndCapabilityTag(UUID agentId, String capabilityTag);

    /** Every specialist registered for a tag, oldest-first (stable, predictable "first match wins" ordering for {@link SpecialistHandoffService#findSpecialist}). */
    List<AgentSpecialization> findByCapabilityTagOrderByCreatedAtAsc(String capabilityTag);

    List<AgentSpecialization> findByAgentId(UUID agentId);

    void deleteByAgentIdAndCapabilityTag(UUID agentId, String capabilityTag);

    /** Every distinct tag any agent in this tenant is registered for - the roster a delegating agent's tool description lists. */
    @Query("SELECT DISTINCT s.capabilityTag FROM AgentSpecialization s ORDER BY s.capabilityTag")
    List<String> findDistinctCapabilityTags();
}
