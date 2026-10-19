package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.modality.ModelModalities;

/**
 * An inline chat attachment payload exceeds the per-message limits (more
 * than {@link ModelModalities#MAX_ATTACHMENTS} items, or more than {@link
 * ModelModalities#MAX_ATTACHMENT_TOTAL_BYTES} decoded bytes in total).
 * Mapped to HTTP 413 in {@code GlobalExceptionHandler} - deliberately a
 * separate type from the Stage-4 multipart {@link
 * AttachmentTooLargeException} (kept at its long-standing 400) so this new
 * inline contract can use the semantically-correct status without changing
 * the existing upload endpoint's behavior under the web console.
 */
public class AttachmentPayloadTooLargeException extends RuntimeException {

    public AttachmentPayloadTooLargeException(String message) {
        super(message);
    }
}
