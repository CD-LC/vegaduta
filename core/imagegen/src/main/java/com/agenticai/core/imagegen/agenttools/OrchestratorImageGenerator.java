package com.agenticai.core.imagegen.agenttools;

import com.agenticai.core.agentspi.ImageGenerator;
import com.agenticai.core.imagegen.ImageGenNotConfiguredException;
import com.agenticai.core.imagegen.ImageGenerationOrchestratorService;
import org.springframework.stereotype.Component;

/**
 * The kernel's {@link ImageGenerator} over {@link ImageGenerationOrchestratorService} (provider
 * choice, pool, quota).
 */
@Component
public class OrchestratorImageGenerator implements ImageGenerator {

    private final ImageGenerationOrchestratorService orchestrator;

    public OrchestratorImageGenerator(ImageGenerationOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public GeneratedImage generate(String prompt, String size) {
        try {
            ImageGenerationOrchestratorService.GeneratedImageResult r =
                    orchestrator.generate(prompt, size);
            return new GeneratedImage(r.bytes(), r.contentType(), r.model(), r.provider());
        } catch (ImageGenNotConfiguredException e) {
            throw new NotConfiguredException(e.getMessage(), e);
        }
    }
}
