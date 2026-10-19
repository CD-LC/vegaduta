package com.agenticai.core.guardrails.moderation;

import java.util.UUID;

/** Phase P6. No {@link ModerationReviewItem} exists for the given id (→ 404). */
public class ModerationReviewNotFoundException extends RuntimeException {

    public ModerationReviewNotFoundException(UUID id) {
        super("No moderation review item found with id " + id);
    }
}
