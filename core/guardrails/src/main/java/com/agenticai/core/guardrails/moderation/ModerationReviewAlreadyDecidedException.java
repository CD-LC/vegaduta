package com.agenticai.core.guardrails.moderation;

import java.util.UUID;

/**
 * Phase P6. A reviewer tried to decide an item that is no longer {@code
 * PENDING} - another reviewer already resolved it (→ 409 Conflict). Prevents
 * one reviewer silently overwriting another's decision.
 */
public class ModerationReviewAlreadyDecidedException extends RuntimeException {

    public ModerationReviewAlreadyDecidedException(UUID id, ModerationReviewStatus current) {
        super("Moderation review item " + id + " is already resolved (" + current + ") and cannot be decided again");
    }
}
