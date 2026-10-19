package com.agenticai.core.quality.eval;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The single place the eval harness builds its bare (no Spring context) OpenAI-compatible and Anthropic
 * models (ADR-0012 P1b). Same role as core/chat's {@code OpenAiCompatModelBuilder}/{@code
 * AnthropicModelBuilder}, kept separate on purpose: this module deliberately depends on no other core
 * module (see the module description), so it cannot share the chat builders. Phase P3 swaps the Spring AI
 * API classes here and in those two builders. Option values are exactly what {@code EvalRunner} always set.
 */
final class EvalModelBuilder {

    private EvalModelBuilder() {
    }

    /**
     * Any OpenAI-compatible endpoint: Ollama serves /v1/chat/completions under its root base URL; a provider
     * base that already ends in /v1 is handled by OpenAiApi's own baseUrl semantics.
     */
    static OpenAiChatModel openAiCompat(
        String baseUrl, String apiKey, String model, int maxTokens,
        RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder
    ) {
        OpenAiApi openAiApi = OpenAiApi.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .completionsPath("/v1/chat/completions")
            .restClientBuilder(restClientBuilder)
            .webClientBuilder(webClientBuilder)
            .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(model)
            .maxTokens(maxTokens)
            .build();
        return OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(options)
            .build();
    }

    /** No temperature/topP: works across every model, including ones that reject {@code temperature}. */
    static AnthropicChatModel anthropic(
        String apiKey, String model, int maxTokens,
        RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder
    ) {
        AnthropicApi anthropicApi = AnthropicApi.builder()
            .apiKey(apiKey)
            .restClientBuilder(restClientBuilder)
            .webClientBuilder(webClientBuilder)
            .build();
        AnthropicChatOptions options = AnthropicChatOptions.builder()
            .model(model)
            .maxTokens(maxTokens)
            .build();
        return AnthropicChatModel.builder()
            .anthropicApi(anthropicApi)
            .defaultOptions(options)
            .build();
    }
}
