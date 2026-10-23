package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.agentspi.GeneratedArtifactSource;
import com.agenticai.core.elevenlabs.ElevenLabsGeneratedAudioService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ElevenLabs speech ({@code /api/elevenlabs/audio/<uuid>}) for the kernel's {@link
 * GeneratedArtifactSource}.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class ElevenLabsAudioArtifactSource implements GeneratedArtifactSource {

    private final ElevenLabsGeneratedAudioService audioService;

    public ElevenLabsAudioArtifactSource(ElevenLabsGeneratedAudioService audioService) {
        this.audioService = audioService;
    }

    @Override
    public String pathSegment() {
        return "elevenlabs/audio";
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
