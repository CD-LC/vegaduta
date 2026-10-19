package com.agenticai.core.providers.modality;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OpenAI Whisper for video transcription. Requires OpenAI API key configured
 * at the deployment level (via {@code OPENAI_API_KEY} env var or
 * {@code openai.api-key} property).
 *
 * <p>Transcribes the audio track from video files using OpenAI's Whisper model.
 * Supports multiple video formats (MP4, WebM, etc.) by extracting and transcribing
 * the audio component. Video-specific features (frame analysis, OCR) are NOT
 * implemented in this provider - it focuses on audio transcription only.
 *
 * <p>Availability is based on whether an OpenAI API key is configured on the deployment
 * AND the actual transcription call being implemented. Until the OpenAI Java SDK
 * integration lands (see {@link #TRANSCRIPTION_IMPLEMENTED}), this provider reports
 * unavailable unconditionally - mirroring SARVAM_VIDEO's always-false availability -
 * so {@link VideoProviderValidator} rejects agent create/update selections instead of
 * letting tenants pick a provider whose {@code transcribe()} hard-throws at runtime.
 */
@Component
@ConditionalOnProperty(name = "openai.api-key", matchIfMissing = false)
public class OpenAIWhisperVideoProvider implements VideoToTextProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIWhisperVideoProvider.class);

    /**
     * Whether the actual OpenAI Whisper transcription call has been wired up.
     * {@link #transcribe} is still a placeholder that hard-throws "not yet
     * implemented", so this stays {@code false} to keep {@link #isAvailable()}
     * false and the provider unselectable (same UNAVAILABLE surface as
     * SARVAM_VIDEO). Flip to {@code true} only once transcribe() actually calls
     * the OpenAI API.
     */
    private static final boolean TRANSCRIPTION_IMPLEMENTED = false;

    private final @Nullable String apiKey;
    private final String apiUrl;

    public OpenAIWhisperVideoProvider(
        @Value("${openai.api-key:#{null}}") @Nullable String apiKey,
        @Value("${openai.api-url:https://api.openai.com/v1}") String apiUrl
    ) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
    }

    @Override
    public String transcribe(byte[] videoBytes, String filename) throws VideoToTextException {
        return transcribe(videoBytes, filename, "whisper-1", null);
    }

    @Override
    public String transcribe(byte[] videoBytes, String filename, @Nullable String model, @Nullable String languageCode)
        throws VideoToTextException {
        if (videoBytes == null || videoBytes.length == 0) {
            throw new VideoToTextTranscriptionException("Video bytes cannot be empty");
        }

        if (!isAvailable()) {
            throw new VideoToTextProviderException(unavailabilityMessage());
        }

        String effectiveModel = StringUtils.hasText(model) ? model : "whisper-1";

        try {
            // TODO: Implement actual OpenAI Whisper video transcription call
            // This would typically involve:
            // 1. Extracting audio from video (or sending full video depending on OpenAI's API)
            // 2. Making a POST request to OpenAI's audio transcription endpoint
            // 3. Parsing the response and returning the text
            // 4. Handling language code parameter if OpenAI supports it
            log.info(
                "Transcribing video via OpenAI Whisper (model={}, videoSize={}, filename={})",
                effectiveModel, videoBytes.length, filename
            );

            // Placeholder implementation - will be wired to actual OpenAI client
            throw new VideoToTextProviderException(
                "OpenAI Whisper video transcription not yet implemented - awaiting OpenAI Java SDK integration"
            );
        } catch (VideoToTextException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI Whisper video transcription failed", e);
            throw new VideoToTextProviderException("Failed to transcribe video via OpenAI Whisper", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return TRANSCRIPTION_IMPLEMENTED && StringUtils.hasText(apiKey);
    }

    @Override
    public @Nullable String getUnavailabilityReason() {
        return isAvailable() ? null : unavailabilityMessage();
    }

    /** Why this provider cannot run; only meaningful while {@link #isAvailable()} is false. */
    private String unavailabilityMessage() {
        if (!TRANSCRIPTION_IMPLEMENTED) {
            return "OpenAI Whisper Video is not yet implemented on this platform version - coming soon.";
        }
        return "OpenAI Whisper Video is not available. Please configure an OpenAI API key "
            + "(openai.api-key property or OPENAI_API_KEY environment variable) on this deployment.";
    }

    @Override
    public VideoModelProvider getProvider() {
        return VideoModelProvider.OPENAI_WHISPER_VIDEO;
    }
}
