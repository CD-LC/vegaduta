package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.agentspi.SpeakCapabilityProvider;
import com.agenticai.core.sarvam.SarvamCredentialService;
import com.agenticai.core.sarvam.SarvamGeneratedAudioService;
import com.agenticai.core.sarvam.SarvamTextToSpeechClient;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Tenant's own (BYOK) Sarvam credential - tried after {@code
 * elevenlabs.agenttools.ElevenLabsSpeakCapabilityProvider} (a tenant's cloned ElevenLabs voice wins
 * if both are configured), mirroring the old hand-written chain's second tier. Audio is one-step
 * here (2026-08-03, "make audio work if the user requests it") - the moment a tenant activates
 * their own Sarvam key, {@code speak} is available on all their agents; the per-agent {@code
 * isSarvamEnabled} opt-in only gates the OTHER Sarvam tools (translate/transliterate/identify) in
 * {@code AgentToolProvider#resolveSarvamTools}, not this one. Sarvam TTS resolves the tenant's own
 * key, so there is no uncapped platform spend here.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamSpeakCapabilityProvider implements SpeakCapabilityProvider {

    private final ObjectProvider<SarvamCredentialService> credentialService;
    private final ObjectProvider<SarvamTextToSpeechClient> client;
    private final ObjectProvider<SarvamGeneratedAudioService> audioService;

    public SarvamSpeakCapabilityProvider(
            ObjectProvider<SarvamCredentialService> credentialService,
            ObjectProvider<SarvamTextToSpeechClient> client,
            ObjectProvider<SarvamGeneratedAudioService> audioService) {
        this.credentialService = credentialService;
        this.client = client;
        this.audioService = audioService;
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public Optional<SpeakBackend> resolve() {
        SarvamCredentialService svCredentialService = credentialService.getIfAvailable();
        SarvamTextToSpeechClient svClient = client.getIfAvailable();
        SarvamGeneratedAudioService svAudioService = audioService.getIfAvailable();
        if (svCredentialService == null || svClient == null || svAudioService == null) {
            return Optional.empty();
        }
        if (!svCredentialService.isActive()) {
            return Optional.empty();
        }
        return Optional.of(new SarvamSpeakBackend(svClient, svAudioService));
    }
}
