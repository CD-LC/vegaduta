package com.agenticai.core.imagegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.LlmProviderCredentialRepository;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderStatus;
import com.agenticai.core.providers.modelcost.MediaModality;
import com.agenticai.core.providers.modelcost.MediaModelCostService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Provider order is cost order, in two tiers (BYOK, then pool) - and identical to the old fixed order under the seeded costs. */
class ImageGenerationOrchestratorCostOrderTest {

    private final LlmProviderCredentialRepository credentials = mock(LlmProviderCredentialRepository.class);
    private final ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
    private final OpenAiImageGenerationClient openAi = mock(OpenAiImageGenerationClient.class);
    private final ImageGenerationClientRegistry registry = mock(ImageGenerationClientRegistry.class);
    private final GeminiImageGenerationClient pool = mock(GeminiImageGenerationClient.class);
    private final ImageGenerationQuotaService quota = mock(ImageGenerationQuotaService.class);
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final MediaModelCostService costs = mock(MediaModelCostService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MediaModelCostService> costsProvider = mock(ObjectProvider.class);
    private ImageGenerationOrchestratorService orchestrator;

    @BeforeEach
    void setUp() {
        when(costsProvider.getIfAvailable()).thenReturn(costs);
        // PlatformSettingsService#getString(key, default) returns the default when the key is unset.
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        orchestrator = new ImageGenerationOrchestratorService(
            credentials, resolver, openAi, registry, pool, quota, mock(TenantLimits.class), settings, costsProvider,
            "imagen-3.0-generate-002", "gemini-2.5-flash-image");
    }

    private void active(ProviderName... providers) {
        for (ProviderName provider : providers) {
            when(credentials.existsByProviderNameAndStatus(provider, ProviderStatus.ACTIVE)).thenReturn(true);
        }
    }

    @Test
    void seededCostsKeepOpenAiFirstExactlyAsTheOldFixedOrderDid() {
        active(ProviderName.OPENAI, ProviderName.GOOGLE_VERTEX);
        when(costs.providerRank(MediaModality.IMAGE, "OPENAI")).thenReturn(0);
        when(costs.providerRank(MediaModality.IMAGE, "GOOGLE_VERTEX")).thenReturn(2);

        assertEquals(List.of(ProviderName.OPENAI, ProviderName.GOOGLE_VERTEX), orchestrator.activeByokProvidersCheapestFirst());
    }

    @Test
    void aCheaperVertexRowMovesVertexAheadOfOpenAiWithNoCodeChange() {
        active(ProviderName.OPENAI, ProviderName.GOOGLE_VERTEX);
        when(costs.providerRank(MediaModality.IMAGE, "OPENAI")).thenReturn(1);
        when(costs.providerRank(MediaModality.IMAGE, "GOOGLE_VERTEX")).thenReturn(0);
        when(resolver.resolveActiveKey(ProviderName.GOOGLE_VERTEX)).thenReturn("vertex-key");
        when(costs.cheapestModel(MediaModality.IMAGE, "GOOGLE_VERTEX")).thenReturn(Optional.of("imagen-4.0-fast"));
        when(registry.generate(eq(ProviderName.GOOGLE_VERTEX), eq("vertex-key"), eq("a cat"), eq("imagen-4.0-fast")))
            .thenReturn(new ImageGenerationClient.GeneratedImage(new byte[] {1}, "image/png", "imagen-4.0-fast"));

        ImageGenerationOrchestratorService.GeneratedImageResult result = orchestrator.generate("a cat", null);

        assertEquals("GOOGLE_VERTEX", result.provider());
        assertEquals("imagen-4.0-fast", result.model());
        verify(openAi, never()).generate(any(), any(), any());
    }

    @Test
    void unlistedProvidersKeepTheirOriginalRelativeOrder() {
        active(ProviderName.OPENAI, ProviderName.GOOGLE_VERTEX);
        when(costs.providerRank(any(), anyString())).thenReturn(Integer.MAX_VALUE);

        assertEquals(List.of(ProviderName.OPENAI, ProviderName.GOOGLE_VERTEX), orchestrator.activeByokProvidersCheapestFirst());
    }

    @Test
    void aTenantsOwnKeyOutranksTheCheaperPlatformPool() {
        active(ProviderName.OPENAI);
        when(pool.isAvailable()).thenReturn(true);
        when(openAi.generate("a cat", null, null))
            .thenReturn(new OpenAiImageGenerationClient.GeneratedImageBytes(new byte[] {1}, "image/png", "gpt-image-1", "1024x1024"));

        assertEquals("OPENAI", orchestrator.generate("a cat", null).provider());
        verify(pool, never()).generate(any(), any());
    }

    @Test
    void poolUsesTheCheapestListedModelUnlessAnOperatorPinnedOne() {
        when(pool.isAvailable()).thenReturn(true);
        when(costs.cheapestModel(MediaModality.IMAGE, "GOOGLE")).thenReturn(Optional.of("gemini-cheap-image"));
        when(pool.generate(eq("a cat"), anyString())).thenAnswer(inv ->
            new GeminiImageGenerationClient.GeneratedImageBytes(new byte[] {1}, "image/png", inv.getArgument(1)));

        assertEquals("gemini-cheap-image", orchestrator.generate("a cat", null).model());

        when(settings.getString(eq("image.generation.pool-model"), any())).thenReturn("operator-pinned");
        assertEquals("operator-pinned", orchestrator.generate("a cat", null).model());
    }
}
