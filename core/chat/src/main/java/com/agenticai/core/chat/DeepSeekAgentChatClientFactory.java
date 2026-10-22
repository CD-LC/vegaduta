package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * Bonus OpenAI-compatible provider (provider fan-out, July 2026) - DeepSeek's API is
 * OpenAI-chat-completions-compatible, so this reuses Spring AI's {@code
 * spring-ai-starter-model-openai} module with a fixed {@code baseUrl}, the exact same shape as
 * {@link OpenRouterAgentChatClientFactory}/ {@link GrokAgentChatClientFactory} (no dedicated Spring
 * AI starter exists for DeepSeek either).
 *
 * <p>Model id is the agent's free-text {@code model} field, e.g. {@code "deepseek-chat"} or {@code
 * "deepseek-reasoner"} - see DeepSeek's own model list at api-docs.deepseek.com.
 */
@Component
public class DeepSeekAgentChatClientFactory implements AgentChatClientFactory {

    // NOT "https://api.deepseek.com/v1" - see OpenRouterAgentChatClientFactory's
    // BASE_URL comment for why: OpenAiApi already appends its own default
    // completionsPath ("/v1/chat/completions") onto baseUrl.
    private static final String BASE_URL = "https://api.deepseek.com";

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    private final MeterRegistry meterRegistry;
    private final LlmPricingCatalog pricingCatalog;
    // Shared across every factory in this package so no provider is left able
    // to have a turn killed by its own model's malformed or invented tool call -
    // see ResilientToolCallingManagerFactory.
    private final ResilientToolCallingManagerFactory toolCallingManagers;

    // Outbound HTTP timeouts (2026-07-29 security audit) - OpenAiApi's own
    // default RestClient/WebClient have none at all. See LlmHttpClientFactory.
    private final LlmHttpClientFactory llmHttpClientFactory;
    // Bounded retries, real ObservationRegistry, default advisors, stream usage -
    // see LlmModelSupport.
    private final LlmModelSupport modelSupport;
    // Shared OpenAI-compatible transport/model construction (ADR-0012 P1b) - see
    // OpenAiCompatModelBuilder.
    private final OpenAiCompatModelBuilder modelBuilder;

    public DeepSeekAgentChatClientFactory(
            LlmCallMetricsService llmCallMetricsService,
            LlmCallLogService llmCallLogService,
            MeterRegistry meterRegistry,
            LlmPricingCatalog pricingCatalog,
            LlmHttpClientFactory llmHttpClientFactory,
            ResilientToolCallingManagerFactory toolCallingManagers,
            LlmModelSupport modelSupport) {
        this.modelSupport = modelSupport;
        this.llmCallMetricsService = llmCallMetricsService;
        this.llmCallLogService = llmCallLogService;
        this.meterRegistry = meterRegistry;
        this.pricingCatalog = pricingCatalog;
        this.llmHttpClientFactory = llmHttpClientFactory;
        this.toolCallingManagers = toolCallingManagers;
        this.modelBuilder =
                new OpenAiCompatModelBuilder(
                        llmHttpClientFactory, toolCallingManagers, modelSupport);
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.DEEPSEEK;
    }

    @Override
    public ChatClient create(
            String apiKey, String model, int maxTokens, double temperature, double topP) {
        return create(apiKey, model, maxTokens, temperature, topP, java.util.Map.of());
    }

    /**
     * Applies the optional {@link SamplingOptions} the provider's API supports; unsupported fields
     * are omitted.
     */
    @Override
    public ChatClient create(
            String apiKey,
            String model,
            int maxTokens,
            double temperature,
            double topP,
            java.util.Map<String, Object> providerOptions) {
        SamplingOptions sampling = SamplingOptions.from(providerOptions);
        OpenAiApi openAiApi = modelBuilder.api(BASE_URL).apiKey(apiKey).build();

        var optionsBuilder =
                modelBuilder
                        .options(ProviderName.DEEPSEEK, model)
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP);
        sampling.applyTo(optionsBuilder);
        LlmModelSupport.logDroppedSampling(ProviderName.DEEPSEEK, model, sampling, "topK");
        OpenAiChatOptions options = optionsBuilder.build();

        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport
                .chatClientBuilder(chatModel)
                .defaultAdvisors(
                        modelSupport.advisors(
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.DEEPSEEK,
                                        model,
                                        meterRegistry,
                                        pricingCatalog)))
                .build();
    }
}
