package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.agentspi.SpeakCapabilityProvider;
import com.agenticai.core.elevenlabs.ElevenLabsClient;
import com.agenticai.core.elevenlabs.ElevenLabsCredentialService;
import com.agenticai.core.elevenlabs.ElevenLabsGeneratedAudioService;
import com.agenticai.core.elevenlabs.ElevenLabsVoiceService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Tenant's own (BYOK) ElevenLabs credential + at least one cloned voice - the highest-priority
 * {@code speak} backend, mirroring the top of the old hand-written priority chain in {@code
 * AgentToolProvider#resolveAudioTools} (see that method's pre-2026-08-09 history). Gated on a
 * cheap, non-throwing {@code isActive()}/{@code hasAnyVoice()} check rather than {@code
 * resolveActiveApiKey()}/{@code mostRecent()}, which throw.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class ElevenLabsSpeakCapabilityProvider implements SpeakCapabilityProvider {

    private final ObjectProvider<ElevenLabsCredentialService> credentialService;
    private final ObjectProvider<ElevenLabsClient> client;
    private final ObjectProvider<ElevenLabsVoiceService> voiceService;
    private final ObjectProvider<ElevenLabsGeneratedAudioService> audioService;

    public ElevenLabsSpeakCapabilityProvider(
            ObjectProvider<ElevenLabsCredentialService> credentialService,
            ObjectProvider<ElevenLabsClient> client,
            ObjectProvider<ElevenLabsVoiceService> voiceService,
            ObjectProvider<ElevenLabsGeneratedAudioService> audioService) {
        this.credentialService = credentialService;
        this.client = client;
        this.voiceService = voiceService;
        this.audioService = audioService;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public Optional<SpeakBackend> resolve() {
        ElevenLabsCredentialService elCredentialService = credentialService.getIfAvailable();
        ElevenLabsClient elClient = client.getIfAvailable();
        ElevenLabsVoiceService elVoiceService = voiceService.getIfAvailable();
        ElevenLabsGeneratedAudioService elAudioService = audioService.getIfAvailable();
        if (elCredentialService == null
                || elClient == null
                || elVoiceService == null
                || elAudioService == null) {
            return Optional.empty();
        }
        if (!elCredentialService.isActive() || !elVoiceService.hasAnyVoice()) {
            return Optional.empty();
        }
        return Optional.of(new ElevenLabsSpeakBackend(elClient, elAudioService, elVoiceService));
    }
}
