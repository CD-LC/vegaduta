package com.agenticai.core.quality.gate;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentEvalGateRepository extends JpaRepository<AgentEvalGate, UUID> {

    Optional<AgentEvalGate> findByAgentId(UUID agentId);
}
