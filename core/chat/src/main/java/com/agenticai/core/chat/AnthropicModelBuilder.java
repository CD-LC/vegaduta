package com.agenticai.core.chat;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;

/**
 * The single place the Anthropic chat factory builds its Spring AI transport and model (ADR-0012 P1b),
 * mirroring {@link OpenAiCompatModelBuilder}: bounded-timeout HTTP clients from {@link
 * LlmHttpClientFactory}, and the shared tool-calling manager, retry template and observation registry
 * on the model. Phase P3 swaps {@code AnthropicApi}/{@code AnthropicChatModel} by editing this class.
 * (The eval module cannot depend on core/chat; it has its own equivalent seam.)
 */
final class AnthropicModelBuilder {

    private final LlmHttpClientFactory httpClients;
    private final ResilientToolCallingManagerFactory toolCallingManagers;
    private final LlmModelSupport modelSupport;

    AnthropicModelBuilder(
        LlmHttpClientFactory httpClients,
        ResilientToolCallingManagerFactory toolCallingManagers,
        LlmModelSupport modelSupport
    ) {
        this.httpClients = httpClients;
        this.toolCallingManagers = toolCallingManagers;
        this.modelSupport = modelSupport;
    }

    AnthropicApi api(String apiKey) {
        return AnthropicApi.builder()
            .apiKey(apiKey)
            .restClientBuilder(httpClients.restClientBuilder())
            .webClientBuilder(httpClients.webClientBuilder())
            .build();
    }

    AnthropicChatModel chatModel(AnthropicApi api, AnthropicChatOptions options) {
        return AnthropicChatModel.builder()
            .anthropicApi(api)
            .defaultOptions(options)
            .toolCallingManager(toolCallingManagers.create())
            .retryTemplate(modelSupport.retryTemplate())
            .observationRegistry(modelSupport.observationRegistry())
            .build();
    }
}
