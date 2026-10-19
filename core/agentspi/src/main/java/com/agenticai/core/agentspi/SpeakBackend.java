package com.agenticai.core.agentspi;

/**
 * One text-to-speech provider {@code tools.audio.SpeakToolCallback} can be backed by - the audio
 * counterpart of {@code com.agenticai.core.imagegen.ImageGenerationClient}. {@code
 * tools.audio.SpeakBackendRegistry} picks which implementation (if any) is eligible for the current
 * tenant and the callback is constructed with exactly one already-resolved backend, so the tool
 * itself never branches on provider - see {@link SpeakCapabilityProvider} for the priority order
 * and gates. Kernel-owned (ADR-0011 rule a): implementations live in {@code elevenlabs.agenttools}
 * and {@code sarvam.agenttools}.
 */
public interface SpeakBackend {

    /**
     * @param audioUrl a {@code /api/.../audio/{id}} reference - a tool result can only return text,
     *     never raw audio bytes.
     */
    record SpokenAudio(String audioUrl, String detail) {}

    /**
     * Short label surfaced in the tool result so a person (or log) can tell which provider actually
     * spoke - e.g. {@code "ELEVENLABS"}, {@code "SARVAM"}.
     */
    String providerLabel();

    /**
     * @param languageCode optional BCP-47 code (e.g. {@code "hi-IN"}) - honored by providers that
     *     support multiple languages, ignored by providers (like a single cloned voice) that don't.
     * @param voiceHint optional provider-specific voice/speaker id or name - same
     *     ignore-if-not-applicable contract.
     */
    SpokenAudio speak(String text, String languageCode, String voiceHint);
}
