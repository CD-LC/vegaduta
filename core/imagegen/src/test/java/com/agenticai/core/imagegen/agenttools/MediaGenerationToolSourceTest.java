package com.agenticai.core.imagegen.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.MediaToolSource;
import com.agenticai.core.imagegen.GeneratedImageService;
import com.agenticai.core.imagegen.ImageGenerationOrchestratorService;
import com.agenticai.core.imagegen.VertexVeoVideoGenerationClient;
import com.agenticai.core.imagegen.VideoGenerationJobService;
import com.agenticai.core.providers.LlmProviderCredentialRepository;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** generate_image is BYOK-or-pool; generate_video is BYOK only (the pool is never consulted). */
class MediaGenerationToolSourceTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    @Test
    void imageIsOfferedForAByokProviderOrThePool() {
        ImageGenerationOrchestratorService orchestrator =
                mock(ImageGenerationOrchestratorService.class);
        ImageGenerationToolSource source =
                new ImageGenerationToolSource(
                        provider(orchestrator), provider(mock(GeneratedImageService.class)));
        assertEquals(MediaToolSource.Kind.IMAGE, source.kind());

        assertTrue(source.rawTools().isEmpty(), "neither BYOK nor pool");

        when(orchestrator.isPoolAvailable()).thenReturn(true);
        assertEquals("generate_image", source.rawTools().get(0).getToolDefinition().name());

        when(orchestrator.isPoolAvailable()).thenReturn(false);
        when(orchestrator.isAnyProviderActive()).thenReturn(true);
        assertEquals(1, source.rawTools().size());
    }

    @Test
    void imageNeedsItsBeans() {
        assertTrue(
                new ImageGenerationToolSource(provider(null), provider(null)).rawTools().isEmpty());
    }

    @Test
    void videoIsOfferedOnlyForTheTenantsOwnVertexCredential() {
        LlmProviderCredentialRepository credentials = mock(LlmProviderCredentialRepository.class);
        VertexVeoVideoGenerationClient veo = mock(VertexVeoVideoGenerationClient.class);
        when(veo.isPoolAvailable()).thenReturn(true);
        VideoGenerationToolSource source =
                new VideoGenerationToolSource(
                        provider(mock(VideoGenerationJobService.class)),
                        provider(credentials),
                        provider(veo));
        assertEquals(MediaToolSource.Kind.VIDEO, source.kind());

        assertTrue(source.rawTools().isEmpty(), "pool alone does not offer video");

        when(credentials.existsByProviderNameAndStatus(
                        ProviderName.GOOGLE_VERTEX, ProviderStatus.ACTIVE))
                .thenReturn(true);
        assertEquals("generate_video", source.rawTools().get(0).getToolDefinition().name());
        verify(credentials, org.mockito.Mockito.times(2))
                .existsByProviderNameAndStatus(ProviderName.GOOGLE_VERTEX, ProviderStatus.ACTIVE);
    }

    @Test
    void videoNeedsItsBeans() {
        LlmProviderCredentialRepository credentials = mock(LlmProviderCredentialRepository.class);
        assertTrue(
                new VideoGenerationToolSource(provider(null), provider(credentials), provider(null))
                        .rawTools()
                        .isEmpty());
        verifyNoInteractions(credentials);
    }
}
