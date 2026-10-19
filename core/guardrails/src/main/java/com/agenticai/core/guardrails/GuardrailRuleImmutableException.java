package com.agenticai.core.guardrails;

import java.util.UUID;

/**
 * Thrown when a caller (any plan, including UNLIMITED) tries to update or
 * delete a {@link GuardrailRuleType#DEFAULT_TEMPLATE} rule. Templates are
 * platform-seeded safety-net presets, not tenant-owned data - they stay fully
 * readable/usable by every tenant but are never editable by anyone through
 * {@link GuardrailRuleService}. Distinct from the FREE-tier authoring gate
 * ({@link com.agenticai.core.security.TenantLimitExceededException}): this
 * fires regardless of plan.
 *
 * <p>Handled locally by {@link GuardrailRuleController} (403 Forbidden - the
 * request is well-formed, the caller may be fully authorized to author its
 * own rules, but this specific row is a fixed platform default and never
 * mutable), same "controller-local exception mapping" pattern as {@link
 * GuardrailRuleNotFoundException}.
 */
public class GuardrailRuleImmutableException extends RuntimeException {

    public GuardrailRuleImmutableException(UUID id) {
        super("Guardrail rule " + id + " is a platform default template and cannot be modified or deleted - "
            + "author a new custom rule instead.");
    }
}
