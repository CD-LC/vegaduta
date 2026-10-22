package com.agenticai.core.elevenlabs;

import com.agenticai.core.providers.modality.TextToSpeechException;
import com.agenticai.core.providers.modality.TextToSpeechProvider;
import com.agenticai.core.providers.modality.TextToSpeechProviderException;
import com.agenticai.core.providers.modality.TextToSpeechSynthesisException;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Text-to-speech provider for ElevenLabs. Wraps the SAME {@link ElevenLabsClient#textToSpeech} call
 * {@code VoiceCallController}'s default fallback chain already uses, so quota-checking ({@code
 * VoiceQuotaService}, internal to that client) and text normalization apply identically here - no
 * duplicated logic.
 *
 * <p>Voice resolution mirrors {@code VoiceCallController#synthesizeSpeech}'s existing default: the
 * tenant's most recently cloned voice ({@link ElevenLabsVoiceService}) if one exists, else the
 * public "Rachel" voice ({@link #DEFAULT_VOICE_ID}, same constant value as {@code
 * VoiceCallController#DEFAULT_ELEVENLABS_VOICE_ID}).
 *
 * <p>Requires an active {@code ElevenLabsCredential} for the tenant. If not configured, {@link
 * #isAvailable()} returns false and agent creation/update selecting this provider is rejected by
 * {@code TextToSpeechProviderValidator}.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class ElevenLabsTextToSpeechProvider implements TextToSpeechProvider {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsTextToSpeechProvider.class);

    /**
     * Public "Rachel" voice - same fallback {@code VoiceCallController} uses when the tenant has no
     * cloned voice of their own.
     */
    static final String DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM";

    private final ElevenLabsClient ttsClient;
    private final ElevenLabsCredentialService credentialService;
    private final ElevenLabsVoiceService voiceService;

    public ElevenLabsTextToSpeechProvider(
            ElevenLabsClient ttsClient,
            ElevenLabsCredentialService credentialService,
            ElevenLabsVoiceService voiceService) {
        this.ttsClient = ttsClient;
        this.credentialService = credentialService;
        this.voiceService = voiceService;
    }

    @Override
    public byte[] synthesize(String text) throws TextToSpeechException {
        return synthesize(text, null, null);
    }

    @Override
    public byte[] synthesize(String text, String model, String voiceOrSpeaker)
            throws TextToSpeechException {
        if (!StringUtils.hasText(text)) {
            throw new TextToSpeechSynthesisException("No text was provided to speak.");
        }
        if (!isAvailable()) {
            throw new TextToSpeechProviderException(getUnavailabilityReason(), 0, false);
        }

        String resolvedVoiceId =
                StringUtils.hasText(voiceOrSpeaker) ? voiceOrSpeaker : resolveDefaultVoiceId();

        try {
            byte[] audio = ttsClient.textToSpeech(resolvedVoiceId, text, model);
            if (audio == null || audio.length == 0) {
                throw new TextToSpeechProviderException("ElevenLabs returned no audio.", 0, true);
            }
            return audio;
        } catch (TextToSpeechException | VoiceQuotaExceededException e) {
            // VoiceQuotaExceededException propagates as-is (unchecked) - same
            // contract VoiceCallController's own callers already handle;
            // this method doesn't declare it since it's a RuntimeException.
            throw e;
        } catch (RuntimeException e) {
            log.warn("ElevenLabs TTS synthesis failed: {}", e.getMessage());
            throw new TextToSpeechProviderException(
                    "ElevenLabs text-to-speech failed: " + e.getMessage(), e, true);
        }
    }

    private String resolveDefaultVoiceId() {
        return voiceService.hasAnyVoice()
                ? voiceService.mostRecent().getElevenLabsVoiceId()
                : DEFAULT_VOICE_ID;
    }

    @Override
    public boolean isAvailable() {
        try {
            return credentialService.isActive();
        } catch (Exception e) {
            log.debug("Error checking ElevenLabs availability: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getUnavailabilityReason() {
        return "ElevenLabs text-to-speech is not available. Please configure an ElevenLabs API key in your tenant settings (/providers).";
    }

    @Override
    public TtsModelProvider getProvider() {
        return TtsModelProvider.ELEVENLABS;
    }
}
