package com.agenticai.core.chat.structured;

import java.util.Map;

/**
 * A per-call request for provider-enforced structured output, carried to the provider factories
 * through the {@code providerOptions} map under {@link #OPTION_KEY} (same channel as
 * {@code SamplingOptions}). A factory that supports native schemas (OpenAI, Gemini, Vertex) applies
 * it via {@link StructuredOutput#withJsonSchema} behind the {@code
 * app.llm.structured-output.native-enabled} kill-switch; every other factory ignores it.
 *
 * <p>Only set this on tool-free utility calls: forcing a JSON schema on every turn of a
 * tool-calling conversation prevents the model from emitting tool calls.
 */
public record StructuredSchemaSpec(String name, String jsonSchema) {

    /** {@code providerOptions} key. */
    public static final String OPTION_KEY = "structuredSchema";

    /** Reads {@link #OPTION_KEY}; null when absent, wrong type or blank schema. */
    public static StructuredSchemaSpec from(Map<String, Object> providerOptions) {
        if (providerOptions != null && providerOptions.get(OPTION_KEY) instanceof StructuredSchemaSpec s
            && s.jsonSchema() != null && !s.jsonSchema().isBlank()) {
            return s;
        }
        return null;
    }

    /** Convenience: a one-entry providerOptions map requesting {@code schema}. */
    public static Map<String, Object> options(String name, String jsonSchema) {
        return Map.of(OPTION_KEY, new StructuredSchemaSpec(name, jsonSchema));
    }
}
