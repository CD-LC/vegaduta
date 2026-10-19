package com.agenticai.core.workflow.dto;

import com.agenticai.core.workflow.WebhookSignatureMode;

/**
 * Read view of a webhook trigger node's signature config (V482):
 * {@code configured} is true when verification is on and a secret is stored.
 * The secret itself is never in this record.
 */
public record WebhookSignatureResponse(String nodeId, WebhookSignatureMode mode, boolean configured) {
}
