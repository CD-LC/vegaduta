package com.agenticai.core.workflow.dto;

import com.agenticai.core.workflow.WebhookSignatureMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Signature-verification config for a WEBHOOK trigger node (V482), written
 * via {@code PUT /api/workflows/{id}/nodes/{nodeId}/webhook/signature}. The
 * secret is stored AES-256-GCM-encrypted and never readable back - rotation
 * means PUT a new one; removing verification is {@code mode: "NONE"} or
 * DELETE.
 */
public record WebhookSignatureRequest(
    @NotNull WebhookSignatureMode mode,
    @Size(max = 512) String secret
) {
}
