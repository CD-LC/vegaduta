package com.agenticai.core.guardrails;

import java.util.UUID;

/**
 * No {@link GuardrailRule} exists with the given id in the calling tenant's
 * schema. Handled locally by {@link GuardrailRuleController} (404), same
 * "controller-local exception mapping precedes the app-wide advice" pattern
 * {@code ModerationReviewController} already establishes in this module -
 * keeps this feature self-contained to {@code core/guardrails} without
 * editing the shared {@code GlobalExceptionHandler} in {@code core/app}.
 */
public class GuardrailRuleNotFoundException extends RuntimeException {

    public GuardrailRuleNotFoundException(UUID id) {
        super("No guardrail rule found with id " + id);
    }
}
