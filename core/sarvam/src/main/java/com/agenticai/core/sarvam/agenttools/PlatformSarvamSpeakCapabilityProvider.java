package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.agentspi.SpeakCapabilityProvider;
import com.agenticai.core.sarvam.SarvamGeneratedAudioService;
import com.agenticai.core.sarvam.SarvamTextToSpeechClient;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Platform-subsidised Sarvam fallback (2026-08-03) - lowest priority: only used when the tenant has
 * neither an ElevenLabs voice nor its own Sarvam key, but the operator configured a platform Sarvam
 * key. Speaks via that so audio "just works", per-tenant quota-capped ({@link
 * AudioGenerationQuotaService}) so it can never become an unbounded bill. Never shadows a tenant's
 * own BYOK choice or the platform-ElevenLabs fallback one tier up.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class PlatformSarvamSpeakCapabilityProvider implements SpeakCapabilityProvider {

    private final ObjectProvider<SarvamTextToSpeechClient> client;
    private final ObjectProvider<SarvamGeneratedAudioService> audioService;
    private final ObjectProvider<AudioGenerationQuotaService> quotaService;
    private final String platformSarvamKey;

    public PlatformSarvamSpeakCapabilityProvider(
            ObjectProvider<SarvamTextToSpeechClient> client,
            ObjectProvider<SarvamGeneratedAudioService> audioService,
            ObjectProvider<AudioGenerationQuotaService> quotaService,
            @Value("${app.audio.generation.platform-sarvam-key:}") String platformSarvamKey) {
        this.client = client;
        this.audioService = audioService;
        this.quotaService = quotaService;
        this.platformSarvamKey = platformSarvamKey;
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public Optional<SpeakBackend> resolve() {
        if (platformSarvamKey == null || platformSarvamKey.isBlank()) {
            return Optional.empty();
        }
        SarvamTextToSpeechClient svClient = client.getIfAvailable();
        SarvamGeneratedAudioService svAudioService = audioService.getIfAvailable();
        AudioGenerationQuotaService quota = quotaService.getIfAvailable();
        if (svClient == null || svAudioService == null || quota == null) {
            return Optional.empty();
        }
        return Optional.of(
                new PlatformSarvamSpeakBackend(svClient, svAudioService, quota, platformSarvamKey));
    }
}
