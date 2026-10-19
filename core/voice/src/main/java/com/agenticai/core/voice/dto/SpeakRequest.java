package com.agenticai.core.voice.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/voice/agents/{agentId}/speak} request body - arbitrary
 * text to synthesize (e.g. an already-rendered chat reply), independent of
 * the STT/chat pipeline that {@code /turn} runs.
 */
public record SpeakRequest(
    /**
     * <p><b>{@code @Size} is a DoS control (2026-07-29).</b> This record used
     * to carry no validation at all, and {@code VoiceCallController#speak}
     * feeds it straight into {@code SpeechTextNormalizer#normalizeForSpeech},
     * whose markdown/HTML rules were quadratic on unterminated delimiters -
     * measured 170 seconds of pinned CPU for one request. The normalizer is
     * now bounded internally as well ({@code
     * SpeechTextNormalizer.MAX_INPUT_CHARS}), which is what protects its other
     * five call sites; this cap is what turns an over-long request into a
     * cheap 400 instead of work the server performs and then discards.
     *
     * <p>5,000 characters is the largest single-request limit of any
     * synthesizer sitting behind this endpoint - ElevenLabs caps at 5,000,
     * Sarvam bulbul at ~1,500, Twilio {@code <Say>} at 4,096 - so nothing that
     * would ever have produced audio is rejected by this bound.
     *
     * <p>Only {@code @Size} is declared, deliberately: the blank/missing case
     * is already answered by {@code VoiceCallController#speak}'s own
     * {@code StringUtils.hasText} check with a purpose-written message, and
     * adding {@code @NotBlank} here would change that response's shape for no
     * benefit.
     */
    @Size(max = 5000, message = "text is too long (5000 character maximum)")
    String text
) {
}
