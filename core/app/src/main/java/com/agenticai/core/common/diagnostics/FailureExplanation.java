package com.agenticai.core.common.diagnostics;

/**
 * Output of {@link FailureExplanationService#explain}: the same underlying failure,
 * explained at four depths (see {@link ExplanationTier}). Every field is already
 * masked - see {@link SensitiveDataMasker} - no caller needs to (or should) run
 * masking again on any of these strings.
 *
 * @param basic plain language, no jargon, for a non-technical end user.
 * @param intermediate a bit more detail, still consumer-friendly, no HTTP status
 *     codes or exception class names.
 * @param technical for a technical user/developer - real error category, no raw
 *     secrets or stack traces.
 * @param expertTechnical maximum real detail an on-call engineer would want -
 *     exception class names, provider/tool names, config key names - still masked of
 *     actual secret VALUES.
 * @param llmGenerated {@code true} only when {@code technical}/{@code
 *     expertTechnical} came from {@link LlmBackedTechnicalStrategy}'s LLM fallback
 *     branch rather than its template lookup - lets a caller/admin UI surface a
 *     "best-effort" indicator if it wants to.
 */
public record FailureExplanation(
    String basic,
    String intermediate,
    String technical,
    String expertTechnical,
    boolean llmGenerated
) {
}
