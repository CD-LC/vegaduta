package com.agenticai.core.workflow.version;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, UUID> {

    List<WorkflowVersion> findByWorkflowIdOrderByVersionNumberDesc(UUID workflowId);

    Optional<WorkflowVersion> findByWorkflowIdAndVersionNumber(UUID workflowId, int versionNumber);

    /** The ordinal the next snapshot takes; null when this workflow has never been versioned. */
    @Query("SELECT MAX(v.versionNumber) FROM WorkflowVersion v WHERE v.workflowId = :workflowId")
    Integer latestVersionNumber(@Param("workflowId") UUID workflowId);

    /** The current holder of a label - V342's partial unique index on {@code (workflow_id, label)} makes at most one row possible. */
    Optional<WorkflowVersion> findByWorkflowIdAndLabel(UUID workflowId, com.agenticai.core.agents.version.VersionLabel label);

    /** The newest snapshot - the resolution fallback when no PRODUCTION label exists. */
    Optional<WorkflowVersion> findFirstByWorkflowIdOrderByVersionNumberDesc(UUID workflowId);
}
