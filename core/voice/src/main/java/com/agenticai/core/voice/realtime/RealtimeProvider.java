package com.agenticai.core.voice.realtime;

/**
 * Native speech-to-speech providers (P2 product-completeness item:
 * "Speech-to-speech voice - no Gemini Live/OpenAI Realtime native path").
 *
 * <p>These bypass the STT &rarr; LLM &rarr; TTS pipeline entirely: audio goes
 * into the provider's bidirectional WebSocket and audio comes back, cutting
 * the per-turn latency floor from "transcribe + first token + first TTS
 * sentence" to the provider's own audio-to-audio time. The classic pipeline
 * stays the default; a caller opts into a realtime session per call via
 * {@link RealtimeVoiceSessionController}.
 */
public enum RealtimeProvider {
    GEMINI_LIVE,
    OPENAI_REALTIME;

    /**
     * Parses the request/URL spelling ({@code "gemini-live"}, {@code
     * "openai-realtime"}, or the enum name itself, case-insensitive).
     * Returns null for anything else - callers decide whether to 400.
     */
    public static RealtimeProvider parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "gemini-live", "gemini_live", "gemini" -> GEMINI_LIVE;
            case "openai-realtime", "openai_realtime", "openai" -> OPENAI_REALTIME;
            default -> null;
        };
    }
}
