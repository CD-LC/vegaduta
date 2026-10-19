package com.agenticai.core.providers.modality;

import org.jspecify.annotations.Nullable;

/**
 * Abstraction for speech-to-text (STT) providers. Each implementation handles
 * transcription of audio bytes into text using a specific backend (Sidecar
 * Whisper, Sarvam, etc.).
 *
 * <p>Implementations are registered with {@link SpeechToTextProviderFactory}
 * and selected by {@code VoiceCallController} based on the agent's
 * {@code voiceModelProvider} field.
 *
 * <p>All implementations MUST be thread-safe and handle provider-specific
 * errors (network failures, API rate limits, invalid credentials) by throwing
 * the appropriate exception type (see subclass javadocs).
 *
 * @see SidecarWhisperProvider
 * @see SarvamSpeechToTextProvider
 */
public interface SpeechToTextProvider {

    /**
     * Transcribes the given audio bytes into text using this provider's backend.
     *
     * @param audioBytes the audio data (typically WAV, MP3, OGG, etc. -
     *                   provider-specific for exact format support)
     * @param filename   optional filename or metadata hint (used by some
     *                   providers for logging/debugging, can be null)
     * @return the transcribed text
     * @throws SpeechToTextTranscriptionException if transcription fails
     *         (empty audio, unsupported format, etc.) - the caller should
     *         treat this as "the user should try speaking more clearly"
     * @throws SpeechToTextProviderException if a provider-level error occurs
     *         (API unreachable, credentials invalid, rate limited) - the
     *         caller should fall back to another provider or fail the turn
     */
    String transcribe(byte[] audioBytes, String filename) throws SpeechToTextException;

    /**
     * Transcribes audio with optional model/language overrides specific to
     * this provider. Default implementation delegates to {@link #transcribe}.
     *
     * @param audioBytes   the audio data
     * @param filename     optional filename/metadata hint
     * @param model        provider-specific model name (e.g., "saaras:v3" for
     *                     Sarvam, ignored by Sidecar Whisper)
     * @param languageCode optional BCP-47 language code or "unknown" for
     *                     auto-detect (e.g., "hi-IN", "en-US")
     * @return the transcribed text
     * @throws SpeechToTextException on any transcription or provider error
     */
    default String transcribe(byte[] audioBytes, String filename, @Nullable String model, @Nullable String languageCode)
        throws SpeechToTextException {
        // Default: ignore model/languageCode overrides, just call basic transcribe
        return transcribe(audioBytes, filename);
    }

    /**
     * Checks whether this provider is currently available on this deployment.
     * Used by validation logic (AgentService) to reject agent creation/updates
     * for providers that aren't configured.
     *
     * <p>Examples:
     * <ul>
     *   <li>Sidecar Whisper: returns false if the sidecar is down or the
     *       "knowledge" profile is inactive</li>
     *   <li>Sarvam: returns false if no active SarvamCredential exists for
     *       this tenant</li>
     *   <li>ElevenLabs: returns false if no active ElevenLabs credential exists</li>
     * </ul>
     *
     * @return true if this provider can successfully transcribe audio on this
     *         deployment, false if it's unavailable
     */
    boolean isAvailable();

    /**
     * A human-readable reason why this provider is unavailable, if
     * {@link #isAvailable()} returns false. Used in validation error messages.
     *
     * <p>Example: "Sidecar Whisper is not available. Please enable the
     * knowledge profile or ensure the sidecar service is running."
     *
     * @return a non-null string explaining why the provider is unavailable,
     *         or null if {@link #isAvailable()} returns true
     */
    @Nullable String getUnavailabilityReason();

    /**
     * Returns the {@link VoiceModelProvider} enum this implementation handles.
     */
    VoiceModelProvider getProvider();
}
