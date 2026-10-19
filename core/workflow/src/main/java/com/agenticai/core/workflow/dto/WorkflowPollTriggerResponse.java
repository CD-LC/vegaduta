package com.agenticai.core.workflow.dto;

import java.time.Instant;

/**
 * Read view of a poll trigger (V482). Deliberately carries NO secret
 * material: {@code headersConfigured} is the only header fact that ever
 * leaves the API (same posture as the webhook signature endpoints, which
 * return mode + configured, never the secret). Read-open like the GET on the
 * workflow schedule path - the sensitive verbs are admin-gated in
 * {@code ToolChannelWorkflowRules}.
 */
public record WorkflowPollTriggerResponse(
    String nodeId,
    boolean configured,
    String url,
    String method,
    Integer intervalSeconds,
    String itemsPath,
    String idPath,
    boolean seedOnEnable,
    boolean enabled,
    boolean headersConfigured,
    Instant nextPollAt,
    Instant lastPolledAt,
    String lastStatus,
    int consecutiveFailures
) {
    public static WorkflowPollTriggerResponse unconfigured(String nodeId) {
        return new WorkflowPollTriggerResponse(
            nodeId, false, null, null, null, null, null, true, false, false, null, null, null, 0);
    }
}
