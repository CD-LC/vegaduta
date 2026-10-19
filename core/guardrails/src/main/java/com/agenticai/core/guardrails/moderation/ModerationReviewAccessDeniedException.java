package com.agenticai.core.guardrails.moderation;

/**
 * Phase P6. The caller is authenticated but lacks the trust-&-safety
 * reviewer authority required to read or act on the moderation review queue
 * (→ 403). Mapped by {@code ModerationReviewController}'s local handler.
 */
public class ModerationReviewAccessDeniedException extends RuntimeException {

    public ModerationReviewAccessDeniedException() {
        super("Trust-&-safety reviewer role required to access the moderation review queue");
    }
}
