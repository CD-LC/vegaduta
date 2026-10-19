package com.agenticai.core.agents.dto;

import com.agenticai.core.providers.modality.TextModelProvider;
import jakarta.validation.constraints.NotBlank;

/**
 * PATCH /api/agents/{id}/text-model - explicit text model provider selection for
 * this agent. Allows agents to route text/LLM completions through a specific
 * provider (ANTHROPIC, OPENAI, GEMINI, etc.) rather than using the agent's
 * primary provider.
 *
 * <p>Both fields are required together - an agent cannot have a provider
 * without a model, or vice versa. Null values (via omission) clear the
 * selection and revert to the agent's primary provider/model pair.
 *
 * <p>The selected provider must be available on this deployment at the time
 * of the request - AgentService validates this and rejects unavailable providers.
 */
public record AgentTextModelUpdateRequest(
    // Nullable - null means "clear the selection and use agent's primary provider/model"
    TextModelProvider textModelProvider,
    @NotBlank(message = "textModel is required when textModelProvider is set") String textModel
) {
    /**
     * Trims {@code textModel} before validation, same pattern as
     * {@link AgentModelUpdateRequest}. A model id with leading/trailing
     * spaces would be sent verbatim to the provider and rejected.
     */
    public AgentTextModelUpdateRequest {
        if (textModel != null) {
            textModel = textModel.trim();
        }
    }

    /**
     * Convenience factory for clearing the text model selection.
     */
    public static AgentTextModelUpdateRequest clear() {
        return new AgentTextModelUpdateRequest(null, null);
    }

    /**
     * Convenience factory for setting a specific provider/model pair.
     */
    public static AgentTextModelUpdateRequest of(TextModelProvider provider, String model) {
        return new AgentTextModelUpdateRequest(provider, model);
    }
}
