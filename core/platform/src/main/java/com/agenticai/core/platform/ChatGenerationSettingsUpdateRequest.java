package com.agenticai.core.platform;

/**
 * Body for {@code PUT /api/platform/chat-generation-settings} - see {@link
 * ChatGenerationSettingsResponse} for what each knob does and which
 * {@code platform_setting} key backs it. Every field is optional/nullable -
 * a null field leaves that setting's stored row untouched (partial update),
 * same posture as {@link FreeTierQuotaConfigUpdateRequest}. Validation
 * (rejected as 400 via {@code IllegalArgumentException} -> {@code
 * GlobalExceptionHandler}): {@code geminiReasoningEffort} must be one of
 * {@code none|low|medium|high}; {@code historyMaxTurns} 1-500; {@code
 * historyMaxChars} 1000-200000.
 *
 * @param geminiReasoningEffort thinking effort for hosted-pool Gemini rows, or null to keep.
 * @param historyMaxTurns       max replayed conversation turns (1-500), or null to keep.
 * @param historyMaxChars       max replayed history characters (1000-200000), or null to keep.
 */
public record ChatGenerationSettingsUpdateRequest(
    String geminiReasoningEffort,
    Integer historyMaxTurns,
    Integer historyMaxChars
) {
}
