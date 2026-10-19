package com.agenticai.core.imagegen.agenttools;

import com.agenticai.core.imagegen.GeneratedImageService;
import com.agenticai.core.imagegen.ImageGenNotConfiguredException;
import com.agenticai.core.imagegen.ImageGenerationOrchestratorService;
import com.agenticai.core.imagegen.ImageGenerationQuotaExceededException;
import com.agenticai.core.providers.ProviderNotActiveException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes {@link ImageGenerationOrchestratorService} as a native Spring AI tool -
 * Master-Roadmap-2026.md item #3, generalized (2026-08-02) from OpenAI-only to whichever image
 * provider the tenant has actually configured (OpenAI or Google Vertex - see the orchestrator's own
 * javadoc for the priority order and why). Mirrors {@code
 * com.agenticai.core.agents.tools.audio.SpeakToolCallback} exactly: a tool result can only return
 * text, never raw binary, so this callback stores the generated image via {@link
 * GeneratedImageService} and returns a {@code /api/imagegen/images/{id}} reference instead of the
 * image bytes themselves.
 */
public class GenerateImageToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GenerateImageToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final ImageGenerationOrchestratorService orchestrator;
    private final GeneratedImageService imageService;

    public GenerateImageToolCallback(
            ImageGenerationOrchestratorService orchestrator, GeneratedImageService imageService) {
        this.orchestrator = orchestrator;
        this.imageService = imageService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("generate_image")
                .description(
                        "Generates an image from a text description and returns an imageUrl that displays the image "
                                + "INLINE in the chat. This tool cannot return image bytes directly, so to show the image to "
                                + "the user you MUST include the returned imageUrl verbatim in your reply (the chat renders any "
                                + "/api/imagegen/images/... URL inline). Use it when the user asks to create, draw, generate, or "
                                + "illustrate an image, picture, graphic, icon, or artwork from a description.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "prompt": {"type": "string", "description": "A detailed description of the image to generate"},
                        "size": {"type": "string", "description": "Optional image dimensions, e.g. '1024x1024', '1024x1536', '1536x1024'. Defaults to 1024x1024."}
                    },
                    "required": ["prompt"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String prompt = asString(args.get("prompt"));
            if (prompt == null || prompt.isBlank()) {
                return "{\"error\": \"prompt is required\"}";
            }
            String size = asString(args.get("size"));
            ImageGenerationOrchestratorService.GeneratedImageResult result =
                    orchestrator.generate(prompt, size);
            UUID imageId =
                    imageService.store(
                            result.bytes(),
                            result.contentType(),
                            prompt,
                            result.model(),
                            size,
                            result.provider());
            return MAPPER.writeValueAsString(
                    Map.of(
                            "status",
                            "success",
                            "imageUrl",
                            "/api/imagegen/images/" + imageId,
                            "model",
                            result.model(),
                            "instructions",
                            "The image was generated successfully. To show it to the user you MUST include this "
                                    + "exact URL on its own line in your reply: /api/imagegen/images/"
                                    + imageId
                                    + " — the chat renders it inline as the image. Do NOT claim you cannot generate images."));
        } catch (Exception e) {
            log.warn("generate_image tool call failed", e);
            return errorJson(consumerReason(e));
        }
    }

    /**
     * Turns a failure into something the model can actually relay to a person, instead of the raw
     * {@code e.getMessage()} this used to hand back ("Image generation call failed: 401
     * Unauthorized: {"error":...}").
     *
     * <p>Incident that motivated this (2026-07-29): a tenant with no OpenAI credential asked their
     * agent for an image. The failure was never even this specific - the tool was not registered at
     * all, so Spring AI threw {@code No ToolCallback found for tool name: generate_image} and
     * killed the stream (see {@code com.agenticai.core.chat.ArgumentSanitizingToolCallingManager},
     * which fixes that half). This half makes sure that once the tool IS registered, every way it
     * can fail still comes back as a sentence naming the next action rather than a provider error
     * code.
     *
     * <p>Written at the model, not the user: the return value is a tool result, so the person only
     * ever sees the model's own rendering of it in whatever language they are speaking.
     */
    private static String consumerReason(Exception e) {
        if (e instanceof ImageGenerationQuotaExceededException quota) {
            // Already written for a person by ImageGenerationQuotaService - pass it through.
            return quota.getMessage() + " Tell the user this and do not retry the tool.";
        }
        if (e instanceof ProviderNotActiveException
                || e instanceof ImageGenNotConfiguredException) {
            return "Image generation is not switched on for this workspace. It needs an OpenAI or Google Vertex "
                    + "AI credential to be added and activated on the Providers page of the console (image generation "
                    + "reuses whichever of those keys is already active for chat). Tell the user exactly that, and do "
                    + "not retry the tool.";
        }
        return "Image generation could not be completed just now. Tell the user the image could not be "
                + "generated and that they can try again in a moment; answer the rest of their message normally.";
    }

    // Same bug class fixed in KnowledgeSearchToolCallback - hand-built
    // "{\"error\": \"...\"}" strings break if the exception message
    // contains a raw '"'.
    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
