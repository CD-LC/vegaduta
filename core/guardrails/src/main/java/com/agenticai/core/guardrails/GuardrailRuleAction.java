package com.agenticai.core.guardrails;

/**
 * What a {@link GuardrailRule} does when its {@code pattern} matches. Mirrors
 * the two postures {@link PatternBasedGuardrailService} already hardcodes for
 * its own compiled-in pattern lists ({@code HARD_BLOCK_PATTERNS} vs {@code
 * REDACTION_PATTERNS}) - this enum just makes that same choice data-driven
 * and per-rule instead of baked into Java source.
 */
public enum GuardrailRuleAction {

    /** Withhold the content entirely - same posture as a hard-block pattern match. */
    BLOCK,

    /** Replace the matched substring with a redaction marker rather than blocking the whole content. */
    REDACT
}
