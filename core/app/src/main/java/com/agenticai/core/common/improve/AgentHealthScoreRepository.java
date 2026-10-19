package com.agenticai.core.common.improve;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentHealthScoreRepository extends JpaRepository<AgentHealthScore, UUID> {

    Optional<AgentHealthScore> findByAgentId(UUID agentId);
}
