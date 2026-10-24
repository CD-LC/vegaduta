package com.agenticai.core.prompts;

import java.time.Instant;

/**
 * One stored override: the current state of a (scope, scopeId, key, locale).
 *
 * @param scopeId empty for PLATFORM and TENANT, the agent's UUID for AGENT
 * @param text the wording; may be null only when {@code enabled} is false
 * @param enabled false = the prompt is switched off at this scope (only for a disableable key)
 * @param version 1 for the first save, then +1 for every change (revert included)
 */
public record PromptOverride(
        PromptScope scope,
        String scopeId,
        String key,
        String locale,
        String text,
        boolean enabled,
        int version,
        String createdBy,
        Instant createdAt) {}
