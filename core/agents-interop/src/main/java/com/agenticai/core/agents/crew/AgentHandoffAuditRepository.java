package com.agenticai.core.agents.crew;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentHandoffAuditRepository extends JpaRepository<AgentHandoffAudit, UUID> {

    /** Every hop of one turn's handoff chain(s), in dispatch order - the full trace {@link AgentHandoffAuditService#findChainForTurn} exposes. */
    List<AgentHandoffAudit> findByTurnSessionIdOrderByDepthAscCreatedAtAsc(String turnSessionId);
}
