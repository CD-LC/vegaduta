package com.agenticai.core.sarvam;

import com.agenticai.core.providers.modality.SpeechToTextException;
import com.agenticai.core.providers.modality.SpeechToTextProvider;
import com.agenticai.core.providers.modality.SpeechToTextProviderException;
import com.agenticai.core.providers.modality.SpeechToTextTranscriptionException;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Speech-to-text provider for Sarvam AI's speech-to-text API. Supports
 * multi-lingual transcription, translation, transliteration, and code-mixing
 * via the {@code mode} parameter.
 *
 * <p>Requires an active {@code SarvamCredential} for the tenant. If not
 * configured, this provider will report as unavailable and the agent
 * creation/update will be rejected.
 *
 * <p>Models supported:
 * <ul>
 *   <li>{@code saaras:v3} (default) - all 23 languages, supports mode parameter</li>
 *   <li>{@code saarika:v2.5} (legacy) - 12 languages, transcribe-only</li>
 * </ul>
 */
@Component
public class SarvamSpeechToTextProvider implements SpeechToTextProvider {

    private static final Logger log = LoggerFactory.getLogger(SarvamSpeechToTextProvider.class);

    private final SarvamSpeechToTextClient sttClient;
    private final SarvamCredentialAvailabilityChecker credentialChecker;

    public SarvamSpeechToTextProvider(
        SarvamSpeechToTextClient sttClient,
        SarvamCredentialAvailabilityChecker credentialChecker
    ) {
        this.sttClient = sttClient;
        this.credentialChecker = credentialChecker;
    }

    @Override
    public String transcribe(byte[] audioBytes, String filename) throws SpeechToTextException {
        return transcribe(audioBytes, filename, null, null);
    }

    @Override
    public String transcribe(byte[] audioBytes, String filename, String model, String languageCode)
        throws SpeechToTextException {
        if (!isAvailable()) {
            throw new SpeechToTextProviderException(
                getUnavailabilityReason(),
                0,
                false  // not retryable - Sarvam credential is not configured
            );
        }

        String resolvedModel = StringUtils.hasText(model) ? model : VoiceModelProvider.SARVAM.getDefaultModel();

        try {
            SarvamSpeechToTextClient.TranscriptionResult result = sttClient.transcribe(
                audioBytes, filename, resolvedModel, "transcribe", languageCode
            );

            if (result == null || !StringUtils.hasText(result.transcript())) {
                throw new SpeechToTextTranscriptionException(
                    "No speech detected in the audio. Please try speaking more clearly."
                );
            }

            log.debug("Sarvam transcription successful: {} bytes → {} chars (lang={})",
                audioBytes.length, result.transcript().length(), result.languageCode());
            return result.transcript();

        } catch (SarvamNotConfiguredException e) {
            // This shouldn't happen since we checked isAvailable() above,
            // but handle it just in case
            log.warn("Sarvam not configured: {}", e.getMessage());
            throw new SpeechToTextProviderException(
                getUnavailabilityReason(),
                0,
                false
            );
        } catch (SarvamApiException e) {
            log.warn("Sarvam API error: {} (status {})", e.getMessage(), e.getUpstreamStatus());

            boolean retryable = e.getUpstreamStatus() == 0  // network error, retryable
                || (e.getUpstreamStatus() >= 500 && e.getUpstreamStatus() < 600)  // 5xx errors, retryable
                || e.getUpstreamStatus() == 429;  // rate limit, retryable with backoff

            throw new SpeechToTextProviderException(
                "Sarvam speech-to-text failed: " + e.getMessage(),
                e,
                retryable
            );
        } catch (SpeechToTextException e) {
            throw e;  // re-throw our own exceptions as-is
        } catch (Exception e) {
            log.error("Unexpected error during Sarvam transcription: {}", e.getMessage(), e);
            throw new SpeechToTextProviderException(
                "Sarvam encountered an unexpected error: " + e.getMessage(),
                e,
                true
            );
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            return credentialChecker.isSarvamConfigured();
        } catch (Exception e) {
            log.debug("Error checking Sarvam availability: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getUnavailabilityReason() {
        return "Sarvam speech-to-text is not available. Please configure a Sarvam API key in your tenant settings.";
    }

    @Override
    public VoiceModelProvider getProvider() {
        return VoiceModelProvider.SARVAM;
    }
}
