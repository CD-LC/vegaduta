package com.agenticai.core.guardrails.procedures;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentProcedureLinkRepository extends JpaRepository<AgentProcedureLink, UUID> {

    List<AgentProcedureLink> findByAgentIdOrderByPositionAsc(UUID agentId);

    List<AgentProcedureLink> findByProcedureId(UUID procedureId);

    Optional<AgentProcedureLink> findByAgentIdAndProcedureId(UUID agentId, UUID procedureId);

    void deleteByAgentIdAndProcedureId(UUID agentId, UUID procedureId);

    void deleteByProcedureId(UUID procedureId);
}
