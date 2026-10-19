package com.agenticai.core.providers.modality;

import org.jspecify.annotations.Nullable;

/**
 * Abstraction for video-to-text (video STT) providers. Each implementation handles
 * transcription of video bytes/streams into text using a specific backend (OpenAI Whisper,
 * Sidecar Whisper, Sarvam, etc.).
 *
 * <p>Implementations are registered with {@link VideoProviderFactory}
 * and selected by video processing components based on the agent's
 * {@code videoModelProvider} field.
 *
 * <p>All implementations MUST be thread-safe and handle provider-specific
 * errors (network failures, API rate limits, invalid credentials) by throwing
 * the appropriate exception type (see subclass javadocs).
 *
 * <p>Unlike voice transcription (which processes short utterances), video
 * transcription typically involves:
 * <ul>
 *   <li>Extracting audio track from video file</li>
 *   <li>Frame analysis (if the provider supports it)</li>
 *   <li>Combining audio transcription with visual context</li>
 * </ul>
 *
 * @see OpenAIWhisperVideoProvider
 * @see SidecarWhisperVideoProvider
 */
public interface VideoToTextProvider {

    /**
     * Transcribes the given video bytes into text using this provider's backend.
     *
     * @param videoBytes the video data (typically MP4, WebM, etc. -
     *                   provider-specific for exact format support)
     * @param filename   optional filename or metadata hint (used by some
     *                   providers for logging/debugging, can be null)
     * @return the transcribed text (extracted from audio track and/or visual content)
     * @throws VideoToTextTranscriptionException if transcription fails
     *         (unsupported format, corrupted video, no audio track, etc.) - the caller should
     *         treat this as "the video cannot be processed with this provider"
     * @throws VideoToTextProviderException if a provider-level error occurs
     *         (API unreachable, credentials invalid, rate limited) - the
     *         caller should fall back to another provider or fail the operation
     */
    String transcribe(byte[] videoBytes, String filename) throws VideoToTextException;

    /**
     * Transcribes video with optional model/language overrides specific to
     * this provider. Default implementation delegates to {@link #transcribe}.
     *
     * @param videoBytes   the video data
     * @param filename     optional filename/metadata hint
     * @param model        provider-specific model name (e.g., "whisper-1" for
     *                     OpenAI, ignored by others)
     * @param languageCode optional BCP-47 language code or "unknown" for
     *                     auto-detect (e.g., "hi-IN", "en-US")
     * @return the transcribed text
     * @throws VideoToTextException on any transcription or provider error
     */
    default String transcribe(byte[] videoBytes, String filename, @Nullable String model, @Nullable String languageCode)
        throws VideoToTextException {
        // Default: ignore model/languageCode overrides, just call basic transcribe
        return transcribe(videoBytes, filename);
    }

    /**
     * Checks whether this provider is currently available on this deployment.
     * Used by validation logic (AgentService) to reject agent creation/updates
     * for providers that aren't configured.
     *
     * <p>Examples:
     * <ul>
     *   <li>Sidecar Whisper Video: returns false if the sidecar is down or the
     *       "knowledge" profile is inactive</li>
     *   <li>OpenAI Whisper Video: returns false if no OpenAI API key is configured</li>
     *   <li>Sarvam Video: returns false if no active SarvamCredential exists for
     *       this tenant</li>
     * </ul>
     *
     * @return true if this provider can successfully transcribe video on this
     *         deployment, false if it's unavailable
     */
    boolean isAvailable();

    /**
     * A human-readable reason why this provider is unavailable, if
     * {@link #isAvailable()} returns false. Used in validation error messages.
     *
     * <p>Example: "OpenAI Whisper Video is not available. Please configure an
     * OpenAI API key in your deployment settings."
     *
     * @return a non-null string explaining why the provider is unavailable,
     *         or null if {@link #isAvailable()} returns true
     */
    @Nullable String getUnavailabilityReason();

    /**
     * Returns the {@link VideoModelProvider} enum this implementation handles.
     */
    VideoModelProvider getProvider();
}
