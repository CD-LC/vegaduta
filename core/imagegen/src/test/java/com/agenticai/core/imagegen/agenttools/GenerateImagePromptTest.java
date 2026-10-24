package com.agenticai.core.imagegen.agenttools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.imagegen.GeneratedImageService;
import com.agenticai.core.imagegen.ImageGenNotConfiguredException;
import com.agenticai.core.imagegen.ImageGenerationOrchestratorService;
import com.agenticai.core.imagegen.ImageGenerationQuotaExceededException;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The text {@code generate_image} returns to the model is registry text, byte for byte what the tool returned before.
 * The chat module's never-offered answer is a separate key, so the OpenAI-or-Vertex wording is kept here.
 */
class GenerateImagePromptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The "not switched on" text the tool returned before the move to the registry (names both image providers). */
    private static final String UNAVAILABLE =
            "Image generation is not switched on for this workspace. It needs an OpenAI or Google Vertex AI credential to be "
                    + "added and activated on the Providers page of the console (image generation reuses whichever of those keys "
                    + "is already active for chat). Tell the user exactly that, and do not retry the tool.";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final ImageGenerationOrchestratorService orchestrator = mock(ImageGenerationOrchestratorService.class);
    private final GeneratedImageService images = mock(GeneratedImageService.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String errorOf(String result) throws Exception {
        return MAPPER.readTree(result).path("error").asText();
    }

    @Test
    void theShippedTextsAreByteIdenticalToTheLiteralsTheyReplaced() throws Exception {
        GenerateImageToolCallback callback = new GenerateImageToolCallback(orchestrator, images);

        when(orchestrator.generate(any(), any())).thenThrow(new ImageGenNotConfiguredException("none"));
        assertThat(errorOf(callback.call("{\"prompt\":\"a kiln\"}"))).isEqualTo(UNAVAILABLE);

        // doThrow: calling the already-stubbed method inside when(...) would throw the previous stub
        doThrow(new ImageGenerationQuotaExceededException("Limit (5).")).when(orchestrator).generate(any(), any());
        assertThat(errorOf(callback.call("{\"prompt\":\"a kiln\"}")))
                .isEqualTo("Limit (5). Tell the user this and do not retry the tool.");

        doThrow(new IllegalStateException("boom")).when(orchestrator).generate(any(), any());
        assertThat(errorOf(callback.call("{\"prompt\":\"a kiln\"}")))
                .isEqualTo("Image generation could not be completed just now. Tell the user the image could not be "
                        + "generated and that they can try again in a moment; answer the rest of their message normally.");

        UUID id = UUID.randomUUID();
        doReturn(new ImageGenerationOrchestratorService.GeneratedImageResult(new byte[] {1}, "image/png", "m", "OPENAI"))
                .when(orchestrator)
                .generate(any(), any());
        when(images.store(any(), any(), any(), any(), any(), any())).thenReturn(id);
        JsonNode ok = MAPPER.readTree(callback.call("{\"prompt\":\"a kiln\"}"));
        assertThat(ok.path("instructions").asText())
                .isEqualTo("The image was generated successfully. To show it to the user you MUST include this "
                        + "exact URL on its own line in your reply: /api/imagegen/images/" + id
                        + " — the chat renders it inline as the image. Do NOT claim you cannot generate images.");
        assertThat(PromptResolver.defaultsOnly().catalog().problems()).isEmpty();
    }

    @Test
    void aTenantOverrideAndAnAgentOverrideApplyOnTheRealCallPath() throws Exception {
        UUID agent = UUID.randomUUID();
        TenantContext.set(TenantContext.of("t1", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", GenerateImageToolCallback.NOT_CONFIGURED_KEY, "en", "Tenant: no images here.", true, 1, "owner", Instant.now()));
        store.put(new PromptOverride(PromptScope.AGENT, agent.toString(), GenerateImageToolCallback.NOT_CONFIGURED_KEY, "en", "Agent: no images for me.", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        when(orchestrator.generate(any(), any())).thenThrow(new ImageGenNotConfiguredException("none"));

        GenerateImageToolCallback tenantWide = new GenerateImageToolCallback(orchestrator, images).withPrompts(resolver);
        assertThat(errorOf(tenantWide.call("{\"prompt\":\"x\"}"))).isEqualTo("Tenant: no images here.");
        GenerateImageToolCallback forAgent = new GenerateImageToolCallback(orchestrator, images).withPrompts(resolver).forAgent(agent);
        assertThat(errorOf(forAgent.call("{\"prompt\":\"x\"}"))).isEqualTo("Agent: no images for me.");

        // the agent reaches the tool through the media tool source
        ImageGenerationToolSource source = new ImageGenerationToolSource(provider(orchestrator), provider(images));
        source.setPromptResolver(resolver);
        when(orchestrator.isAnyProviderActive()).thenReturn(true);
        assertThat(errorOf(source.rawTools(agent).get(0).call("{\"prompt\":\"x\"}"))).isEqualTo("Agent: no images for me.");
        assertThat(errorOf(source.rawTools().get(0).call("{\"prompt\":\"x\"}"))).isEqualTo("Tenant: no images here.");
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
