package com.agenticai.core.common.diagnostics;

/**
 * Strategy seam for the two single-string tiers ({@code BASIC}, {@code
 * INTERMEDIATE}) - see {@link BasicTierStrategy}/{@link IntermediateTierStrategy}.
 *
 * <p>{@link LlmBackedTechnicalStrategy} (the {@code TECHNICAL} +
 * {@code EXPERT_TECHNICAL} strategy) deliberately does NOT implement this interface:
 * it produces two strings and an {@code llmGenerated} flag from one call rather than
 * a single string, so it exposes its own {@code explain} method with a richer return
 * type instead of forcing that shape through a single-{@code String} contract. Noted
 * explicitly here since the original design sketch grouped all three strategies under
 * one interface - this is a small, deliberate deviation from that sketch, made
 * because BASIC/INTERMEDIATE and TECHNICAL/EXPERT_TECHNICAL genuinely produce
 * different-shaped results (one string vs. two strings + a provenance flag), not
 * because of any ambiguity in what each strategy needs to do.
 */
interface ExplanationTierStrategy {

    String explain(MaskedFailureContext context);
}
