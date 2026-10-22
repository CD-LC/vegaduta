package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.agentspi.MediaToolSource;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code generate_music} through the kernel's {@link MediaToolSource} (ADR-0011 rule a). Offered
 * when the tenant has an active ElevenLabs key of its own OR the platform ElevenLabs key is set
 * ({@link MusicGenerationService#isAvailable} - cheap, non-throwing); {@link
 * MusicGenerationQuotaService} caps the platform-paid path per tenant before the billable call.
 * ObjectProvider so a slim test context without the music service still constructs this bean and
 * simply offers nothing.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class MusicGenerationToolSource implements MediaToolSource {

    private final ObjectProvider<MusicGenerationService> musicGenerationService;

    public MusicGenerationToolSource(
            ObjectProvider<MusicGenerationService> musicGenerationService) {
        this.musicGenerationService = musicGenerationService;
    }

    @Override
    public Kind kind() {
        return Kind.MUSIC;
    }

    @Override
    public List<ToolCallback> rawTools() {
        MusicGenerationService service = musicGenerationService.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            return List.of();
        }
        return List.of(new GenerateMusicToolCallback(service));
    }
}
