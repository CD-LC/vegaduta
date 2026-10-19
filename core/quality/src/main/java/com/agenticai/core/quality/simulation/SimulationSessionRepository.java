package com.agenticai.core.quality.simulation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationSessionRepository extends JpaRepository<SimulationSession, UUID> {

    List<SimulationSession> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    Optional<SimulationSession> findByIdAndAgentId(UUID id, UUID agentId);
}
