package com.agenticai.core.imagegen.agenttools;

import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.agentspi.MediaToolSource;
import com.agenticai.core.imagegen.VertexVeoVideoGenerationClient;
import com.agenticai.core.imagegen.VideoGenerationJobService;
import com.agenticai.core.providers.LlmProviderCredentialRepository;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderStatus;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code generate_video} through the kernel's {@link MediaToolSource} (ADR-0011 rule a). Video is
 * PAID/BYOK-only (product decision 2026-08-03): Veo is expensive (~$0.10-0.75/sec of output), so
 * unlike {@code generate_image} the platform does NOT subsidise it from the pool - the tool is
 * offered only when the tenant has its OWN active GOOGLE_VERTEX credential ({@code
 * veoClient.isPoolAvailable()} is deliberately not consulted). {@code VideoGenerationJobService}
 * keeps its pool path for the anonymous {@code /try} demo surface. The credential check is a cheap,
 * non-throwing boolean.
 */
@Component
public class VideoGenerationToolSource implements MediaToolSource {

    private final ObjectProvider<VideoGenerationJobService> jobService;
    private final ObjectProvider<LlmProviderCredentialRepository> credentialRepository;
    private final ObjectProvider<VertexVeoVideoGenerationClient> veoClient;

    public VideoGenerationToolSource(
            ObjectProvider<VideoGenerationJobService> jobService,
            ObjectProvider<LlmProviderCredentialRepository> credentialRepository,
            ObjectProvider<VertexVeoVideoGenerationClient> veoClient) {
        this.jobService = jobService;
        this.credentialRepository = credentialRepository;
        this.veoClient = veoClient;
    }

    @Override
    public Kind kind() {
        return Kind.VIDEO;
    }

    @Override
    public List<ToolCallback> rawTools() {
        VideoGenerationJobService jobs = jobService.getIfAvailable();
        LlmProviderCredentialRepository credentials = credentialRepository.getIfAvailable();
        if (jobs == null || credentials == null || veoClient.getIfAvailable() == null) {
            return List.of();
        }
        if (!HotpathLookups.<Boolean>tenant(
                "video.vertex-active",
                () ->
                        credentials.existsByProviderNameAndStatus(
                                ProviderName.GOOGLE_VERTEX, ProviderStatus.ACTIVE))) {
            return List.of();
        }
        return List.of(new GenerateVideoToolCallback(jobs));
    }
}
