package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.agentspi.SpeakCapabilityProvider;
import com.agenticai.core.elevenlabs.ElevenLabsClient;
import com.agenticai.core.elevenlabs.ElevenLabsGeneratedAudioService;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Platform-subsidised ElevenLabs fallback (2026-08-03, "even I should be able to add in platform
 * admin" feedback) - tried before {@code sarvam.agenttools.PlatformSarvamSpeakCapabilityProvider},
 * mirroring BYOK ElevenLabs' own priority over BYOK Sarvam one tier up. Requires BOTH a platform
 * API key AND a platform voice id (ElevenLabs has no "default voice", unlike Sarvam's stock voices,
 * so a key with no configured voice cannot actually speak). The credential is admin-UI-settable at
 * runtime via {@code PlatformToolCredentialController}, so it's read fresh on every {@link
 * #resolve()} call rather than cached.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class PlatformElevenLabsSpeakCapabilityProvider implements SpeakCapabilityProvider {

    private final ObjectProvider<PlatformToolCredentialService> credentialService;
    private final ObjectProvider<ElevenLabsClient> client;
    private final ObjectProvider<ElevenLabsGeneratedAudioService> audioService;
    private final ObjectProvider<ElevenLabsGenerationQuotaService> quotaService;

    public PlatformElevenLabsSpeakCapabilityProvider(
            ObjectProvider<PlatformToolCredentialService> credentialService,
            ObjectProvider<ElevenLabsClient> client,
            ObjectProvider<ElevenLabsGeneratedAudioService> audioService,
            ObjectProvider<ElevenLabsGenerationQuotaService> quotaService) {
        this.credentialService = credentialService;
        this.client = client;
        this.audioService = audioService;
        this.quotaService = quotaService;
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public Optional<SpeakBackend> resolve() {
        PlatformToolCredentialService credService = credentialService.getIfAvailable();
        ElevenLabsClient elClient = client.getIfAvailable();
        ElevenLabsGeneratedAudioService elAudioService = audioService.getIfAvailable();
        ElevenLabsGenerationQuotaService elQuota = quotaService.getIfAvailable();
        if (credService == null || elClient == null || elAudioService == null || elQuota == null) {
            return Optional.empty();
        }
        String platformKey =
                credService.resolveActiveApiKey(PlatformToolKey.ELEVENLABS).orElse(null);
        String platformVoiceId =
                credService.resolveActiveExtraConfig(PlatformToolKey.ELEVENLABS).orElse(null);
        if (platformKey == null || platformVoiceId == null) {
            return Optional.empty();
        }
        return Optional.of(
                new PlatformElevenLabsSpeakBackend(
                        elClient, elAudioService, elQuota, platformKey, platformVoiceId));
    }
}
