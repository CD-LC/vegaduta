package com.agenticai.core.workflow.dto;

/**
 * Body of {@code PUT /api/workflows/{id}/nodes/{nodeId}/pin}. {@code source} is one of
 * {@code test|run|manual|trigger_sample} (absent means {@code manual}); {@code configHash}, when absent, is
 * computed by the server from the node's saved config.
 */
public record WorkflowPinRequest(String data, String source, String sourceRunId, String configHash) {
}
