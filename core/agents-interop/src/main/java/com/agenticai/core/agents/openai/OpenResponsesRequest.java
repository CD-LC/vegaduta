package com.agenticai.core.agents.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The subset of an Open Responses / OpenAI Responses {@code POST /v1/responses} body this platform
 * honours (see {@link OpenResponsesInputMapper} for what is accepted, ignored and rejected). Fields
 * whose shape is validated by hand are kept as {@link JsonNode} so a wrong type becomes a precise
 * 400 rather than a generic "invalid JSON". Unknown fields are accepted and ignored, exactly as on
 * {@link OpenAiChatRequest}, so an unmodified SDK never gets a 400 for a field the agent owns
 * anyway ({@code reasoning}, {@code text}, {@code truncation}, {@code top_p}, {@code user}, ...).
 *
 * <p>Spec: https://www.openresponses.org/specification and the OpenAI Responses API reference
 * https://platform.openai.com/docs/api-reference/responses/create.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenResponsesRequest(
        String model,
        JsonNode input,
        JsonNode instructions,
        Double temperature,
        @JsonProperty("max_output_tokens") Integer maxOutputTokens,
        Boolean stream,
        JsonNode metadata,
        @JsonProperty("previous_response_id") String previousResponseId,
        JsonNode tools,
        @JsonProperty("tool_choice") JsonNode toolChoice,
        Boolean background,
        JsonNode conversation) {

    public boolean isStream() {
        return Boolean.TRUE.equals(stream);
    }
}
