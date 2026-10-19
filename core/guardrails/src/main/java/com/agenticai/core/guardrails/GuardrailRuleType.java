package com.agenticai.core.guardrails;

/**
 * 2026-07-27 billing audit follow-up. {@code core/guardrails} previously had
 * no concept of "who authored this rule" at all - every existing safety
 * mechanism ({@link PatternBasedGuardrailService}'s regex lists, {@code
 * ModerationPolicyPack}'s admin-toggled packs) is either hardcoded or
 * platform-admin-only, so there was nothing here for a tenant to author, and
 * therefore nothing plan-gated. This enum is the distinction the billing
 * strategy needs: a {@link GuardrailRule} is either a platform-seeded,
 * read-only safety-net preset every tenant gets for free, or a tenant's own
 * authored rule.
 */
public enum GuardrailRuleType {

    /**
     * A platform-seeded preset (see the {@code guardrail_rule} Flyway seed
     * data). Fully usable and readable by every tenant regardless of plan,
     * but never editable or deletable by anyone through {@link
     * GuardrailRuleService} - see {@link GuardrailRuleImmutableException}.
     */
    DEFAULT_TEMPLATE,

    /**
     * A tenant-authored rule. Authoring (create/update/delete) is a
     * PREMIUM/UNLIMITED feature - see {@link GuardrailRuleService}'s
     * FREE-tier gate, same posture as {@code AgentService#updateModel}'s
     * model-selection gate.
     */
    CUSTOM
}
