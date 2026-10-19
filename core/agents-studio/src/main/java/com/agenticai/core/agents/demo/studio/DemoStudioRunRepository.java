package com.agenticai.core.agents.demo.studio;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoStudioRunRepository extends JpaRepository<DemoStudioRun, UUID> {

    List<DemoStudioRun> findTop10ByWorkflowIdOrderByStartedAtDesc(UUID workflowId);
}
