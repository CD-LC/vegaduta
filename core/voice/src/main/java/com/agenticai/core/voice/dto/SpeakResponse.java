package com.agenticai.core.voice.dto;

/**
 * {@code POST /api/voice/agents/{agentId}/speak} response. {@code
 * audioBase64} is raw audio bytes, base64-encoded, WITHOUT a {@code data:}
 * prefix - MP3 when {@code method} is {@code "elevenlabs"}, WAV when it's
 * {@code "sarvam"}, and empty when TTS wasn't available at all, in which
 * case {@code method} is {@code "text-only-fallback"} and the frontend
 * should fall back to the browser's Web Speech API instead.
 *
 * <p>{@code method} can also come back {@code "quota-exceeded"} (2026-08-08
 * audit fix #1/#2): the tenant's FREE/PREMIUM voice-generation cap ({@code
 * VoiceQuotaService}) rejected the request on EITHER TTS tier before any
 * provider was called - distinct from {@code "text-only-fallback"}
 * specifically so the frontend can render an upgrade prompt instead of
 * treating this like a generic provider failure. {@code audioBase64} is
 * empty in this case too.
 *
 * <p>{@code speechText} is normally the request's text put through {@code
 * SpeechTextNormalizer} - markdown/HTML/emoji/bare URLs rewritten into
 * something a synthesizer should actually say. It is what the frontend must
 * hand to {@code SpeechSynthesisUtterance} in the {@code
 * "text-only-fallback"} case; speaking the raw request text there reads the
 * markdown characters out loud (the 2026-07-29 live bug). When {@code
 * audioBase64} is non-empty this is purely informational - the audio was
 * already synthesized from exactly this string. In the {@code
 * "quota-exceeded"} case, however, this field instead carries {@code
 * VoiceQuotaService}'s own visitor-facing rejection reason (e.g. "...Upgrade
 * for a higher limit.") - NOT text to hand to the Web Speech API.
 */
public record SpeakResponse(String audioBase64, String method, String speechText) {
}
