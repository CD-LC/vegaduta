package com.agenticai.core.providers;

import java.util.Optional;

/**
 * Measures how a model behind an OpenAI-compatible endpoint makes tool calls (see {@link
 * ToolCallGrade}). Implemented in {@code core.chat} ({@code HostedToolCallGradeProbe}), which owns
 * the text-form parser the {@link ToolCallGrade#TEXT} grade is defined by; {@link
 * PlatformHostedLlmProviderService} receives it by optional injection, so the providers module
 * never depends on the chat module and a context without the probe simply stores no grade.
 */
public interface ToolCallCapabilityProbe {

    /**
     * What one probe learned. {@code serverKind} is the kind of server detected while probing
     * (constrained-decoding support differs per kind), {@code parameterBillions} the model's size
     * when the server reports it (Ollama does), otherwise null.
     */
    record Result(ToolCallGrade grade, ServerKind serverKind, Double parameterBillions) {}

    /** The server family behind the base URL, as far as the probe could tell. */
    enum ServerKind {
        /** Ollama: {@code /api/show} answered. Supports JSON-schema {@code format}. */
        OLLAMA,
        /**
         * Ollama whose runner refused a JSON-schema {@code format} for this model (HTTP 400
         * "structured output is unavailable", seen with the MLX runner): no constraint to lean on.
         */
        OLLAMA_WITHOUT_FORMAT,
        /** vLLM: {@code /version} answered. Supports {@code tool_choice: required}. */
        VLLM,
        /** llama.cpp server: {@code /props} answered. Supports {@code response_format: json_schema}. */
        LLAMA_CPP,
        /** Any other OpenAI-compatible endpoint (a hosted provider, a proxy). */
        OTHER
    }

    /**
     * Probes {@code model} at {@code baseUrl} (+ {@code completionsPath}, or {@code
     * /v1/chat/completions} when null). Empty when the server could not be reached or answered in
     * a way that grades nothing, so the caller leaves the stored grade untouched. Never throws.
     *
     * @param apiKeyOrNull bearer token for the chat call; null or blank sends no Authorization
     *     header (a keyless local server).
     */
    Optional<Result> probe(String baseUrl, String completionsPath, String model, String apiKeyOrNull);

    /**
     * Whether the server honours {@code response_format: json_schema} for {@code model}, asked with
     * one tiny request. The default is true (nothing known against it); {@code probe} only asks
     * this for a row that would lean on it, so the local-models probe API asks it separately.
     * Never throws.
     */
    default boolean honoursJsonSchema(String baseUrl, String completionsPath, String model, String apiKeyOrNull) {
        return true;
    }

    /**
     * {@link #probe} for a base URL a TENANT typed (a BYOK local model server): the implementation must use an
     * SSRF-guarded, address-pinned, redirect-free transport, because unlike a platform admin's hosted-pool row the
     * URL is not trusted. The caller still runs the guard's host check first. The default probes nothing, so an
     * implementation that has no guarded transport can never be pointed at a tenant URL by accident.
     */
    default Optional<Result> probeTenantServer(String baseUrl, String completionsPath, String model, String apiKeyOrNull) {
        return Optional.empty();
    }
}
