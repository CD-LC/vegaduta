package com.agenticai.core.imagegen.agenttools;

import com.agenticai.core.imagegen.GeneratedImageService;
import com.agenticai.core.imagegen.ImageGenNotConfiguredException;
import com.agenticai.core.imagegen.ImageGenerationOrchestratorService;
import com.agenticai.core.imagegen.ImageGenerationQuotaExceededException;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
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
    private final PromptSlot prompts = new PromptSlot();
    private volatile UUID agentId;

    /**
     * Registry keys of the text this tool returns to the model. {@link #NOT_CONFIGURED_KEY} is this tool's own
     * answer when no image provider is active; the chat module's stand-in for a tool that was never offered has a
     * separate key ({@code toolresult.unavailable.generate_image}).
     */
    public static final String NOT_CONFIGURED_KEY = "toolresult.image_generation.not_configured";

    public static final String FAILED_KEY = "toolresult.image_generation.failed";
    public static final String QUOTA_SUFFIX_KEY = "toolresult.image_generation.quota_suffix";
    public static final String SUCCESS_INSTRUCTIONS_KEY = "toolresult.image_generation.success_instructions";

    /** Reads the text this tool returns from the prompt registry (agent and tenant of the call); null keeps the shipped text. */
    public GenerateImageToolCallback withPrompts(PromptResolver resolver) {
        prompts.use(resolver);
        return this;
    }

    /** The agent this tool instance serves, so an agent-scope override of its text applies; null = tenant-wide only. */
    public GenerateImageToolCallback forAgent(UUID agent) {
        this.agentId = agent;
        return this;
    }

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
                            prompts.text(
                                    SUCCESS_INSTRUCTIONS_KEY,
                                    agentId,
                                    Map.of("image_url", "/api/imagegen/images/" + imageId))));
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
    private String consumerReason(Exception e) {
        if (e instanceof ImageGenerationQuotaExceededException quota) {
            // Already written for a person by ImageGenerationQuotaService - pass it through.
            return quota.getMessage() + prompts.text(QUOTA_SUFFIX_KEY, agentId);
        }
        if (e instanceof ProviderNotActiveException
                || e instanceof ImageGenNotConfiguredException) {
            return prompts.text(NOT_CONFIGURED_KEY, agentId);
        }
        return prompts.text(FAILED_KEY, agentId);
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
