package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.elevenlabs.ElevenLabsClient;
import com.agenticai.core.elevenlabs.ElevenLabsGeneratedAudioService;
import java.util.UUID;

/**
 * Platform-subsidised voice: speaks via ElevenLabs using VegaDūta's OWN (platform-admin-configured,
 * see {@code PlatformToolCredentialController}) key and voice, so a tenant with no ElevenLabs
 * credential/cloned voice of their own still gets a voice reply. Mirrors {@code
 * sarvam.agenttools.PlatformSarvamSpeakBackend} exactly: quota-capped per tenant ({@link
 * ElevenLabsGenerationQuotaService}) BEFORE the billable call, never after.
 *
 * <p>Chosen by {@code AgentToolProvider#resolveAudioTools} only when the tenant has no BYOK
 * ElevenLabs voice and no BYOK Sarvam key AND a platform ElevenLabs credential + voice id are
 * configured - so it never shadows a tenant's own BYOK choice, and (per that method's existing
 * priority order) is tried before the platform-Sarvam fallback, matching BYOK ElevenLabs' own
 * priority over BYOK Sarvam one tier up.
 */
public class PlatformElevenLabsSpeakBackend implements SpeakBackend {

    private final ElevenLabsClient client;
    private final ElevenLabsGeneratedAudioService audioService;
    private final ElevenLabsGenerationQuotaService quotaService;
    private final String platformApiKey;
    private final String platformVoiceId;

    public PlatformElevenLabsSpeakBackend(
            ElevenLabsClient client,
            ElevenLabsGeneratedAudioService audioService,
            ElevenLabsGenerationQuotaService quotaService,
            String platformApiKey,
            String platformVoiceId) {
        this.client = client;
        this.audioService = audioService;
        this.quotaService = quotaService;
        this.platformApiKey = platformApiKey;
        this.platformVoiceId = platformVoiceId;
    }

    @Override
    public String providerLabel() {
        return "ELEVENLABS";
    }

    @Override
    public SpokenAudio speak(String text, String languageCode, String voiceHint) {
        // Cap first - throws AudioGenerationQuotaExceededException (relayed to
        // the user by SpeakToolCallback) before any billable ElevenLabs call.
        quotaService.checkAllowed();
        // voiceHint is ignored - the platform voice is operator-fixed, unlike
        // the BYOK path where a tenant picks among their own cloned voices.
        byte[] mp3Bytes = client.textToSpeechWithKey(platformVoiceId, text, null, platformApiKey);
        UUID audioId = audioService.store(mp3Bytes, platformVoiceId);
        return new SpokenAudio("/api/elevenlabs/audio/" + audioId, "Platform voice");
    }
}
