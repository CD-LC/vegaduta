package com.agenticai.core.agents.demo.studio;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoStudioWorkflowRepository extends JpaRepository<DemoStudioWorkflow, UUID> {

    long countBySessionId(UUID sessionId);

    List<DemoStudioWorkflow> findBySessionIdInOrderByCreatedAtAsc(List<UUID> sessionIds);
}
