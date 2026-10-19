package com.agenticai.core.sarvam;

import com.agenticai.core.speech.voicenote.VoiceSpeechException;
import com.agenticai.core.speech.voicenote.VoiceSpeechGateway;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link VoiceSpeechGateway} over the tenant's own Sarvam credential (BYOK) - the same clients the
 * agent tools use, so every call lands in the existing {@code sarvam_call_log} (that is the
 * metering). Only registered when the WhatsApp voice-note flag is on, so a default deployment gains
 * no new bean. Lives in the sarvam vendor module and implements the speech module's kernel
 * interface (ADR-0011 rule a); the language table is {@link SarvamIndicLanguages}.
 *
 * <p>Sarvam error text is deliberately NOT propagated: it is not needed for the user message and
 * could echo request details; the failure kind plus the upstream HTTP status is all that is kept.
 *
 * <p>Sarvam endpoints and shapes:
 * https://docs.sarvam.ai/api-reference-docs/speech-to-text/transcribe (30 s REST limit, {@code
 * language_code=unknown} = auto-detect),
 * https://docs.sarvam.ai/api-reference-docs/text-to-speech/convert ({@code output_audio_codec}, 11
 * languages).
 */
@Component
@ConditionalOnProperty(
        prefix = "app.omnichannel.whatsapp.voice-notes",
        name = "enabled",
        havingValue = "true")
public class SarvamVoiceSpeechGateway implements VoiceSpeechGateway {

    private final SarvamCredentialService credentialService;
    private final SarvamSpeechToTextClient sttClient;
    private final SarvamTextToSpeechClient ttsClient;
    private final SarvamCallLogService callLogService;

    public SarvamVoiceSpeechGateway(
            SarvamCredentialService credentialService,
            SarvamSpeechToTextClient sttClient,
            SarvamTextToSpeechClient ttsClient,
            SarvamCallLogService callLogService) {
        this.credentialService = credentialService;
        this.sttClient = sttClient;
        this.ttsClient = ttsClient;
        this.callLogService = callLogService;
    }

    @Override
    public boolean isConfigured() {
        return credentialService.isActive();
    }

    @Override
    public long speechToTextCallsSince(Instant since) {
        return callLogService.countSince(SarvamCallCapability.SPEECH_TO_TEXT, since);
    }

    @Override
    public Transcript transcribe(byte[] audio, String filename, String model, String mode) {
        try {
            SarvamSpeechToTextClient.TranscriptionResult result =
                    sttClient.transcribe(
                            audio, filename, blankToNull(model), blankToNull(mode), "unknown");
            return new Transcript(result.transcript(), result.languageCode());
        } catch (SarvamNotConfiguredException e) {
            throw new VoiceSpeechException(
                    VoiceSpeechException.Failure.NOT_CONFIGURED, "no active Sarvam credential");
        } catch (SarvamApiException e) {
            throw new VoiceSpeechException(
                    VoiceSpeechException.Failure.UPSTREAM_FAILED,
                    "Sarvam speech-to-text failed (upstream status " + e.getUpstreamStatus() + ")");
        }
    }

    @Override
    public byte[] synthesize(
            String text, String languageCode, String model, String speaker, String codec) {
        try {
            return ttsClient
                    .synthesize(
                            text,
                            languageCode,
                            blankToNull(model),
                            blankToNull(speaker),
                            null,
                            blankToNull(codec))
                    .wavBytes();
        } catch (SarvamNotConfiguredException e) {
            throw new VoiceSpeechException(
                    VoiceSpeechException.Failure.NOT_CONFIGURED, "no active Sarvam credential");
        } catch (SarvamApiException e) {
            throw new VoiceSpeechException(
                    VoiceSpeechException.Failure.UPSTREAM_FAILED,
                    "Sarvam text-to-speech failed (upstream status " + e.getUpstreamStatus() + ")");
        }
    }

    @Override
    public String normalizeLanguage(String languageCode) {
        return SarvamIndicLanguages.normalize(languageCode);
    }

    @Override
    public String languageDisplayName(String languageCode) {
        return SarvamIndicLanguages.displayName(languageCode);
    }

    @Override
    public Optional<String> ttsLanguageFor(String detectedLanguageCode) {
        return SarvamIndicLanguages.ttsLanguageFor(detectedLanguageCode);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
