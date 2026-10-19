package com.agenticai.core.workflow.dto;

import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.workflow.WebhookAuthMode;

/**
 * Channel-based trigger selection (#422), now per-node (each Trigger node in
 * a workflow's graph is independently bound to its own inbound channel -
 * {@code nodeId} identifies which one this response describes). {@code
 * webhookAuthMode} is only meaningful while {@code channel == WEBHOOK}; {@code
 * oidcTriggerPath} is non-null only when {@code webhookAuthMode == OAUTH2}
 * (the frontend builds the SHARED_SECRET URL itself from the existing webhook
 * token, same as before - this record doesn't duplicate that).
 */
public record WorkflowTriggerChannelResponse(
    String nodeId, WorkflowTriggerChannel channel, WebhookAuthMode webhookAuthMode, String oidcTriggerPath
) {
}
