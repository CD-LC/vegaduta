package com.agenticai.core.workflow.version;

import com.agenticai.core.agents.version.VersionLabel;
import com.agenticai.core.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The version-list row DTO - everything a history view needs WITHOUT the
 * graph document itself (fetched on demand via the single-version GET, so
 * a 50-version list doesn't ship 50 graphs it never renders).
 */
public record WorkflowVersionResponse(
    UUID id,
    int versionNumber,
    String workflowName,
    WorkflowStatus status,
    String changeSummary,
    String createdBy,
    Instant createdAt,
    // Deployment pointer on this snapshot: STAGING, PRODUCTION or null (unlabelled). At most one
    // version per workflow holds each label, so the history view can badge the live/staged rows.
    VersionLabel label
) {
    public static WorkflowVersionResponse from(WorkflowVersion version) {
        return new WorkflowVersionResponse(
            version.getId(), version.getVersionNumber(), version.getWorkflowName(),
            version.getStatus(), version.getChangeSummary(), version.getCreatedBy(), version.getCreatedAt(), version.getLabel());
    }
}
