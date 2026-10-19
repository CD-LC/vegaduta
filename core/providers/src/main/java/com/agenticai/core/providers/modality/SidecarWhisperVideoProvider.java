package com.agenticai.core.providers.modality;

import com.agenticai.core.knowledge.InvalidKnowledgeUploadException;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.knowledge.SidecarCommunicationException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Local Whisper for video transcription via the sidecar (knowledge/RAG stack).
 * Requires the sidecar to be running and the "knowledge" profile active.
 *
 * <p>Delegates directly to the sidecar's {@code /transcribe} endpoint via
 * {@link SidecarClient#transcribeAudio} - no separate audio-extraction step is
 * needed, because the sidecar's faster-whisper decoding is built on PyAV,
 * which opens video containers (.mp4/.mov/.webm) and decodes just the audio
 * stream (see {@code sidecar/app/transcription.py}'s Stage F3d note). Only the
 * audio track is ever transcribed; frame-level visual analysis is a separate,
 * unbuilt feature. No external API calls, no tenant-specific configuration -
 * just requires the sidecar to be available on the deployment.
 *
 * <p>Availability is based on whether the sidecar client is present and reachable.
 */
@Component
public class SidecarWhisperVideoProvider implements VideoToTextProvider {

    private static final Logger log = LoggerFactory.getLogger(SidecarWhisperVideoProvider.class);

    private final Optional<SidecarClient> sidecarClient;

    public SidecarWhisperVideoProvider(Optional<SidecarClient> sidecarClient) {
        this.sidecarClient = sidecarClient;
    }

    @Override
    public String transcribe(byte[] videoBytes, String filename) throws VideoToTextException {
        return transcribe(videoBytes, filename, null, null);
    }

    @Override
    public String transcribe(byte[] videoBytes, String filename, @Nullable String model, @Nullable String languageCode)
        throws VideoToTextException {
        if (videoBytes == null || videoBytes.length == 0) {
            throw new VideoToTextTranscriptionException("Video bytes cannot be empty");
        }

        if (!isAvailable()) {
            throw new VideoToTextProviderException(
                "Sidecar Whisper Video is not available. Ensure the sidecar is running and the 'knowledge' profile is active."
            );
        }

        // The sidecar runs its own locally-loaded Whisper model and its
        // /transcribe endpoint takes no model/language parameters, so any
        // per-agent model/languageCode overrides are deliberately ignored here
        // (same as the audio sibling, SidecarWhisperProvider).
        //
        // The filename is load-bearing: the sidecar keys its temp-file suffix
        // off the extension (transcription.py's _suffix_of) and rejects
        // unknown extensions with a 400. The interface allows a null filename
        // hint, so fall back to a container-suffixed placeholder - PyAV
        // probes the actual bytes, the suffix is only a hint.
        String effectiveFilename = StringUtils.hasText(filename) ? filename : "video.mp4";

        try {
            String text = sidecarClient.get().transcribeAudio(effectiveFilename, videoBytes);
            if (text == null || text.trim().isEmpty()) {
                throw new VideoToTextTranscriptionException(
                    "No speech detected in the video's audio track."
                );
            }
            log.debug("Sidecar Whisper video transcription successful: {} bytes → {} chars (filename={})",
                videoBytes.length, text.length(), effectiveFilename);
            return text;
        } catch (InvalidKnowledgeUploadException e) {
            // Sidecar 4xx: corrupt/unsupported container, no decodable audio
            // stream, unknown extension. An input problem - another provider
            // would hit the same wall, so this is the non-retryable
            // transcription exception, not the fall-back-able provider one.
            log.warn("Sidecar Whisper rejected the video input: {}", e.getMessage());
            throw new VideoToTextTranscriptionException(
                "The video could not be transcribed: " + e.getMessage(), e);
        } catch (SidecarCommunicationException e) {
            // Sidecar unreachable / 5xx / empty response - provider-level and
            // retryable; the caller should fall back to another provider.
            log.warn("Sidecar Whisper video transcription failed: {}", e.getMessage(), e);
            throw new VideoToTextProviderException(
                "Sidecar Whisper is temporarily unavailable: " + e.getMessage(), e);
        } catch (VideoToTextException e) {
            throw e;  // re-throw our own exceptions as-is
        } catch (Exception e) {
            log.error("Unexpected error during Sidecar Whisper video transcription", e);
            throw new VideoToTextProviderException("Failed to transcribe video via Sidecar Whisper", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return sidecarClient.isPresent();
    }

    @Override
    public @Nullable String getUnavailabilityReason() {
        if (isAvailable()) {
            return null;
        }
        return "Sidecar Whisper Video is not available. Please ensure the sidecar service is running "
            + "and the 'knowledge' profile is active on this deployment.";
    }

    @Override
    public VideoModelProvider getProvider() {
        return VideoModelProvider.SIDECAR_WHISPER_VIDEO;
    }
}
