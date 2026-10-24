package com.agenticai.core.imagegen.agenttools;

import com.agenticai.core.agentspi.MediaToolSource;
import com.agenticai.core.imagegen.GeneratedImageService;
import com.agenticai.core.imagegen.ImageGenerationOrchestratorService;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import com.agenticai.core.prompts.PromptResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code generate_image} through the kernel's {@link MediaToolSource} (ADR-0011 rule a). Offered
 * when the tenant has a BYOK image provider OR the platform Vertex image pool can serve it ({@code
 * isPoolAvailable}) - image on the pool is cheap and per-tenant quota-capped, so subsidising it is
 * fine. No per-agent opt-in. Both checks are cheap, non-throwing booleans.
 */
@Component
public class ImageGenerationToolSource implements MediaToolSource {

    private final ObjectProvider<ImageGenerationOrchestratorService> orchestrator;
    private final ObjectProvider<GeneratedImageService> generatedImageService;
    private volatile PromptResolver prompts;

    public ImageGenerationToolSource(
            ObjectProvider<ImageGenerationOrchestratorService> orchestrator,
            ObjectProvider<GeneratedImageService> generatedImageService) {
        this.orchestrator = orchestrator;
        this.generatedImageService = generatedImageService;
    }

    /** The prompt registry, so the text the tool returns to the model can be overridden; absent = shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        this.prompts = resolver;
    }

    @Override
    public Kind kind() {
        return Kind.IMAGE;
    }

    @Override
    public List<ToolCallback> rawTools() {
        return rawTools(null);
    }

    @Override
    public List<ToolCallback> rawTools(UUID agentId) {
        ImageGenerationOrchestratorService service = orchestrator.getIfAvailable();
        GeneratedImageService images = generatedImageService.getIfAvailable();
        if (service == null || images == null) {
            return List.of();
        }
        if (!service.isAnyProviderActive() && !service.isPoolAvailable()) {
            return List.of();
        }
        return List.of(new GenerateImageToolCallback(service, images).withPrompts(prompts).forAgent(agentId));
    }
}
