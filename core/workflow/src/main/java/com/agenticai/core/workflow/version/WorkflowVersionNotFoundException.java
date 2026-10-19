package com.agenticai.core.workflow.version;

/**
 * 404 for {@code GET /api/workflows/{id}/versions/{version}} when no such
 * snapshot exists - same role {@code WorkflowNotFoundException} plays for
 * definitions and {@code AgentVersionNotFoundException} for agent
 * versions.
 */
public class WorkflowVersionNotFoundException extends RuntimeException {

    public WorkflowVersionNotFoundException(java.util.UUID workflowId, int versionNumber) {
        super("Workflow " + workflowId + " has no version " + versionNumber + ".");
    }
}
