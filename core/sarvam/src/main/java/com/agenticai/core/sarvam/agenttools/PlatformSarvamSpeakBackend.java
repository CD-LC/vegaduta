package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.sarvam.SarvamGeneratedAudioService;
import com.agenticai.core.sarvam.SarvamTextToSpeechClient;
import java.util.UUID;

/**
 * Platform-subsidised voice: speaks via Sarvam using VegaDūta's OWN (operator-configured) Sarvam
 * key rather than the tenant's, so a tenant with no Sarvam credential of their own still gets a
 * voice. The platform pays, so unlike {@link SarvamSpeakBackend} this path is quota-capped per
 * tenant ({@link AudioGenerationQuotaService}) - that cap is the only thing standing between "free
 * audio for everyone" and an unbounded bill, so it runs BEFORE the (billable) synthesize call,
 * never after.
 *
 * <p>Chosen by {@code AgentToolProvider#resolveAudioTools} only when the tenant has neither an
 * ElevenLabs voice nor their own active Sarvam key AND a platform Sarvam key is configured - so it
 * never shadows a tenant's own BYOK choice.
 */
public class PlatformSarvamSpeakBackend implements SpeakBackend {

    private static final String DEFAULT_LANGUAGE = "en-IN";

    private final SarvamTextToSpeechClient client;
    private final SarvamGeneratedAudioService audioService;
    private final AudioGenerationQuotaService quotaService;
    private final String platformApiKey;

    public PlatformSarvamSpeakBackend(
            SarvamTextToSpeechClient client,
            SarvamGeneratedAudioService audioService,
            AudioGenerationQuotaService quotaService,
            String platformApiKey) {
        this.client = client;
        this.audioService = audioService;
        this.quotaService = quotaService;
        this.platformApiKey = platformApiKey;
    }

    @Override
    public String providerLabel() {
        return "SARVAM";
    }

    @Override
    public SpokenAudio speak(String text, String languageCode, String voiceHint) {
        // Cap first - throws AudioGenerationQuotaExceededException (relayed to
        // the user by SpeakToolCallback) before any billable Sarvam call.
        quotaService.checkAllowed();
        String target =
                (languageCode == null || languageCode.isBlank()) ? DEFAULT_LANGUAGE : languageCode;
        SarvamTextToSpeechClient.SynthesisResult result =
                client.synthesize(text, target, null, voiceHint, platformApiKey);
        UUID audioId = audioService.store(result.wavBytes(), "speech.wav");
        return new SpokenAudio("/api/sarvam/audio/" + audioId, target);
    }
}
