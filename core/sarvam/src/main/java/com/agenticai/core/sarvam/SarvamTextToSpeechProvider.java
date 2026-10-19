package com.agenticai.core.sarvam;

import com.agenticai.core.providers.modality.TextToSpeechException;
import com.agenticai.core.providers.modality.TextToSpeechProvider;
import com.agenticai.core.providers.modality.TextToSpeechProviderException;
import com.agenticai.core.providers.modality.TextToSpeechSynthesisException;
import com.agenticai.core.providers.modality.TtsModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Text-to-speech provider for Sarvam AI's text-to-speech API.
 *
 * <p><b>No quota check here</b> - unlike {@code elevenlabs.ElevenLabsTextToSpeechProvider}
 * (whose underlying client quota-checks internally), Sarvam's TTS quota gate
 * ({@code VoiceCallController#checkSarvamQuota}) lives on that controller,
 * not on {@link SarvamTextToSpeechClient}. This provider is registered with
 * {@code TextToSpeechProviderFactory} for validation/discoverability and for
 * any FUTURE caller outside the voice-call path; {@code
 * VoiceCallController}'s own default and explicit-provider routing continues
 * to call its existing, already-quota-checked private helpers ({@code
 * trySarvamTts}/{@code synthesizeSarvamOnly}) rather than this class's {@link
 * #synthesize}, specifically to avoid a quota-bypass regression. See {@link
 * TextToSpeechProvider}'s javadoc for the full explanation.
 *
 * <p>Requires an active {@code SarvamCredential} for the tenant - the same
 * credential {@code VoiceModelProvider#SARVAM} (speech-to-text) already
 * checks for, since one Sarvam key covers both directions.
 */
@Component
public class SarvamTextToSpeechProvider implements TextToSpeechProvider {

    private static final Logger log = LoggerFactory.getLogger(SarvamTextToSpeechProvider.class);

    /** en-IN default target language - same limitation {@code VoiceCallController#synthesizeSarvamOnly} has today (no per-agent target-language selection wired yet). */
    static final String DEFAULT_TARGET_LANGUAGE = "en-IN";

    private final SarvamTextToSpeechClient ttsClient;
    private final SarvamCredentialAvailabilityChecker credentialChecker;

    public SarvamTextToSpeechProvider(
        SarvamTextToSpeechClient ttsClient,
        SarvamCredentialAvailabilityChecker credentialChecker
    ) {
        this.ttsClient = ttsClient;
        this.credentialChecker = credentialChecker;
    }

    @Override
    public byte[] synthesize(String text) throws TextToSpeechException {
        return synthesize(text, null, null);
    }

    @Override
    public byte[] synthesize(String text, String model, String voiceOrSpeaker) throws TextToSpeechException {
        if (!StringUtils.hasText(text)) {
            throw new TextToSpeechSynthesisException("No text was provided to speak.");
        }
        if (!isAvailable()) {
            throw new TextToSpeechProviderException(getUnavailabilityReason(), 0, false);
        }

        try {
            SarvamTextToSpeechClient.SynthesisResult result =
                ttsClient.synthesize(text, DEFAULT_TARGET_LANGUAGE, model, voiceOrSpeaker);
            byte[] wav = result != null ? result.wavBytes() : null;
            if (wav == null || wav.length == 0) {
                throw new TextToSpeechProviderException("Sarvam returned no audio.", 0, true);
            }
            return wav;
        } catch (TextToSpeechException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Sarvam TTS synthesis failed: {}", e.getMessage());
            throw new TextToSpeechProviderException("Sarvam text-to-speech failed: " + e.getMessage(), e, true);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            return credentialChecker.isSarvamConfigured();
        } catch (Exception e) {
            log.debug("Error checking Sarvam TTS availability: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getUnavailabilityReason() {
        return "Sarvam text-to-speech is not available. Please configure a Sarvam API key in your tenant settings.";
    }

    @Override
    public TtsModelProvider getProvider() {
        return TtsModelProvider.SARVAM;
    }
}
