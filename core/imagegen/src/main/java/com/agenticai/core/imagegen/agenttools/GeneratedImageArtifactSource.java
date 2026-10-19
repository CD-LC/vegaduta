package com.agenticai.core.imagegen.agenttools;

import com.agenticai.core.agentspi.GeneratedArtifactSource;
import com.agenticai.core.imagegen.GeneratedImageService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Generated images ({@code /api/imagegen/images/<uuid>}) for the kernel's {@link
 * GeneratedArtifactSource}.
 */
@Component
public class GeneratedImageArtifactSource implements GeneratedArtifactSource {

    private final GeneratedImageService imageService;

    public GeneratedImageArtifactSource(GeneratedImageService imageService) {
        this.imageService = imageService;
    }

    @Override
    public String pathSegment() {
        return "imagegen/images";
    }

    @Override
    public Optional<StoredArtifact> find(UUID id) {
        return imageService
                .find(id)
                .map(
                        image ->
                                new StoredArtifact(
                                        image.getObjectKey(),
                                        image.getContentType(),
                                        image.getFilename()));
    }
}
