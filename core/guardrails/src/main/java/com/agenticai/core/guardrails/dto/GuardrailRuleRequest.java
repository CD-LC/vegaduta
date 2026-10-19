package com.agenticai.core.guardrails.dto;

import com.agenticai.core.guardrails.GuardrailRuleAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a tenant-authored {@code CUSTOM} {@code
 * GuardrailRule}. There is no request DTO for {@code DEFAULT_TEMPLATE} rows -
 * those are Flyway-seeded and never accepted from a client, enforced by
 * {@code GuardrailRuleService} rejecting edits to that type outright
 * regardless of what a request body claims.
 *
 * <p>Size bounds match the {@code guardrail_rule} columns (V145) so an
 * over-long name fails validation with a readable 400 instead of a database
 * constraint violation. {@code pattern} is additionally capped well below the
 * TEXT column it lives in and compiled before it is stored (see {@code
 * GuardrailRuleService}) - it is a regex typed by a customer that this server
 * will execute, so "does it even compile" is checked at authoring time rather
 * than discovered per chat turn.
 *
 * @param enabled nullable on update (omit to leave unchanged); treated as
 *     {@code true} on create when omitted.
 */
public record GuardrailRuleRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 2000) String description,
    @NotBlank @Size(max = 1000) String pattern,
    @NotNull GuardrailRuleAction action,
    Boolean enabled
) {
}
