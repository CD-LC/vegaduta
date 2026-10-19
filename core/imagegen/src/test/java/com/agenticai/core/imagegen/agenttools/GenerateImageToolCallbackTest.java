package com.agenticai.core.imagegen.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.imagegen.GeneratedImageService;
import com.agenticai.core.imagegen.ImageGenNotConfiguredException;
import com.agenticai.core.imagegen.ImageGenerationOrchestratorService;
import com.agenticai.core.imagegen.ImageGenerationQuotaExceededException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The image-generation half of the 2026-07-29 "asking for an image returns a raw sentinel string"
 * incident. The tool never even ran that day (it was not registered at all - see {@code
 * com.agenticai.core.chat.ArgumentSanitizingToolCallingManagerTest}), but the audit that followed
 * found this callback would have been almost as unhelpful if it had: every failure collapsed into
 * {@code "Image generation call failed: " + e.getMessage()}, i.e. a raw provider error handed to
 * the model to read out. These lock in that each distinguishable failure comes back as a sentence
 * naming the next action.
 *
 * <p>Updated 2026-08-04 for the 2026-08-02 image-gen provider-gap generalization: the callback now
 * depends on {@link ImageGenerationOrchestratorService} (which picks whichever of OpenAI/Vertex/
 * the platform pool is active) rather than a raw {@code OpenAiImageGenerationClient} - mocked here
 * at that seam instead.
 */
class GenerateImageToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ImageGenerationOrchestratorService orchestrator =
            mock(ImageGenerationOrchestratorService.class);
    private final GeneratedImageService imageService = mock(GeneratedImageService.class);
    private final GenerateImageToolCallback callback =
            new GenerateImageToolCallback(orchestrator, imageService);

    @Test
    void missingCredentialTellsTheUserWhereToAddOne() {
        when(orchestrator.generate(any(), any()))
                .thenThrow(
                        new ImageGenNotConfiguredException(
                                "No provider in the priority list is active for this tenant."));

        String error = errorOf(callback.call("{\"prompt\": \"a brick kiln at sunrise\"}"));

        String lower = error.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("openai"), error);
        assertTrue(lower.contains("providers"), error);
    }

    @Test
    void quotaRejectionIsPassedThroughVerbatimBecauseItIsAlreadyWrittenForAPerson() {
        when(orchestrator.generate(any(), any()))
                .thenThrow(
                        new ImageGenerationQuotaExceededException(
                                "You've reached today's image generation limit (5)."));

        String error = errorOf(callback.call("{\"prompt\": \"a brick kiln at sunrise\"}"));

        assertTrue(error.startsWith("You've reached today's image generation limit (5)."), error);
    }

    @Test
    void anUnexpectedProviderFailureNeverLeaksTheRawUpstreamMessage() {
        when(orchestrator.generate(any(), any()))
                .thenThrow(
                        new IllegalStateException(
                                "401 Unauthorized: {\"error\":{\"code\":\"invalid_api_key\"}}"));

        String error = errorOf(callback.call("{\"prompt\": \"a brick kiln at sunrise\"}"));

        assertFalse(error.contains("invalid_api_key"), error);
        assertFalse(error.contains("401"), error);
        assertTrue(error.toLowerCase(Locale.ROOT).contains("try again"), error);
    }

    @Test
    void aBlankPromptIsRejectedWithoutCallingTheProvider() {
        assertEquals("prompt is required", errorOf(callback.call("{\"prompt\": \"   \"}")));
    }

    @Test
    void successReturnsAStableImageReferenceRatherThanBytes() {
        UUID id = UUID.randomUUID();
        when(orchestrator.generate(any(), any()))
                .thenReturn(
                        new ImageGenerationOrchestratorService.GeneratedImageResult(
                                new byte[] {1, 2, 3}, "image/png", "gpt-image-1", "OPENAI"));
        when(imageService.store(any(), any(), any(), any(), any(), any())).thenReturn(id);

        JsonNode result = parse(callback.call("{\"prompt\": \"a brick kiln at sunrise\"}"));

        assertEquals("/api/imagegen/images/" + id, result.path("imageUrl").asText());
        assertEquals("gpt-image-1", result.path("model").asText());
    }

    private static String errorOf(String toolResult) {
        return parse(toolResult).path("error").asText();
    }

    private static JsonNode parse(String toolResult) {
        try {
            return MAPPER.readTree(toolResult);
        } catch (Exception e) {
            throw new AssertionError("tool result was not valid JSON: " + toolResult, e);
        }
    }
}
