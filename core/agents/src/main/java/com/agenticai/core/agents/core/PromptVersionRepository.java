package com.agenticai.core.agents.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptVersionRepository extends JpaRepository<PromptVersion, UUID> {

    List<PromptVersion> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    // Scoped by agentId, not just id, so a version id belonging to a
    // different agent (or a different tenant's agent - impossible via SQL
    // injection since schema-per-tenant already isolates the table, but
    // this also guards a same-tenant cross-agent id typo) never resolves.
    Optional<PromptVersion> findByIdAndAgentId(UUID id, UUID agentId);
}
