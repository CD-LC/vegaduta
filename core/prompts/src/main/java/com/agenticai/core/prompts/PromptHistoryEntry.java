package com.agenticai.core.prompts;

import java.time.Instant;

/**
 * One immutable history row: what a change did, who did it, and why. {@code text} is the wording
 * after the change ({@code null} after a revert); {@code previousText} the wording before it.
 */
public record PromptHistoryEntry(
        long id,
        PromptScope scope,
        String scopeId,
        String key,
        String locale,
        int version,
        String action,
        String text,
        boolean enabled,
        String previousText,
        boolean previousEnabled,
        String reason,
        String createdBy,
        Instant createdAt) {

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_REVERT = "REVERT";
    public static final String ACTION_MIGRATE = "MIGRATE";
}
