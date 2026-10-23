package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.agentspi.GeneratedArtifactSource;
import com.agenticai.core.sarvam.SarvamGeneratedAudioService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Sarvam speech ({@code /api/sarvam/audio/<uuid>}) for the kernel's {@link
 * GeneratedArtifactSource}.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamAudioArtifactSource implements GeneratedArtifactSource {

    private final SarvamGeneratedAudioService audioService;

    public SarvamAudioArtifactSource(SarvamGeneratedAudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public String pathSegment() {
        return "sarvam/audio";
    }

    @Override
    public Optional<StoredArtifact> find(UUID id) {
        return audioService
                .find(id)
                .map(
                        audio ->
                                new StoredArtifact(
                                        audio.getObjectKey(),
                                        audio.getContentType(),
                                        audio.getFilename()));
    }
}
