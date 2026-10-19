package com.agenticai.core.workflow;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubagentRunRepository extends JpaRepository<SubagentRun, UUID> {

    List<SubagentRun> findByWorkflowRunIdOrderByCreatedAtAsc(UUID workflowRunId);
}
