package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * Bonus OpenAI-compatible provider (provider fan-out, July 2026) - Mistral AI's API is
 * OpenAI-chat-completions-compatible, so this reuses Spring AI's {@code
 * spring-ai-starter-model-openai} module with a fixed {@code baseUrl}, the exact same shape as
 * {@link OpenRouterAgentChatClientFactory}/ {@link GrokAgentChatClientFactory} (no dedicated Spring
 * AI starter exists for Mistral either).
 *
 * <p>Model id is the agent's free-text {@code model} field, e.g. {@code "mistral-large-latest"} or
 * {@code "open-mistral-nemo"} - see Mistral's own model list at
 * docs.mistral.ai/getting-started/models.
 *
 * <p><b>Prompt caching</b> is delegated to {@link MistralProviderCachingStrategy} (see that class's
 * javadoc for why Mistral's shape is the same routing-hint- only pattern as {@link
 * OpenAIProviderCachingStrategy}).
 */
@Component
public class MistralAgentChatClientFactory implements AgentChatClientFactory {

    // NOT "https://api.mistral.ai/v1" - see OpenRouterAgentChatClientFactory's
    // BASE_URL comment for why: OpenAiApi already appends its own default
    // completionsPath ("/v1/chat/completions") onto baseUrl.
    private static final String BASE_URL = "https://api.mistral.ai";

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
    // See class javadoc's "Prompt caching" section.
    private final MistralProviderCachingStrategy cachingStrategy;
    // Bounded retries, real ObservationRegistry, default advisors, stream usage -
    // see LlmModelSupport.
    private final LlmModelSupport modelSupport;
    // Shared OpenAI-compatible transport/model construction (ADR-0012 P1b) - see
    // OpenAiCompatModelBuilder.
    private final OpenAiCompatModelBuilder modelBuilder;

    public MistralAgentChatClientFactory(
            LlmCallMetricsService llmCallMetricsService,
            LlmCallLogService llmCallLogService,
            MeterRegistry meterRegistry,
            LlmPricingCatalog pricingCatalog,
            LlmHttpClientFactory llmHttpClientFactory,
            ResilientToolCallingManagerFactory toolCallingManagers,
            MistralProviderCachingStrategy cachingStrategy,
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
        this.cachingStrategy = cachingStrategy;
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.MISTRAL;
    }

    @Override
    public ChatClient create(
            String apiKey, String model, int maxTokens, double temperature, double topP) {
        return create(apiKey, model, maxTokens, temperature, topP, java.util.Map.of());
    }

    /**
     * Applies the {@link SamplingOptions} Mistral's chat completions accepts under their OpenAI
     * names - {@code stop}, {@code frequency_penalty}, {@code presence_penalty}. Mistral's seed
     * field is {@code random_seed}, not OpenAI's {@code seed}, and there is no {@code top_k}; both
     * are omitted and DEBUG-logged rather than sent under a name Mistral rejects.
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
                        .options(ProviderName.MISTRAL, model)
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP);
        // Off by default via app.llm.stream-usage.disabled-providers - see LlmModelSupport.
        if (!sampling.stopSequences().isEmpty()) {
            optionsBuilder.stop(sampling.stopSequences());
        }
        if (sampling.frequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(sampling.frequencyPenalty());
        }
        if (sampling.presencePenalty() != null) {
            optionsBuilder.presencePenalty(sampling.presencePenalty());
        }
        LlmModelSupport.logDroppedSampling(ProviderName.MISTRAL, model, sampling, "topK", "seed");
        cachingStrategy.apply(optionsBuilder, new ProviderCachingContext(apiKey, model));
        OpenAiChatOptions options = optionsBuilder.build();

        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport
                .chatClientBuilder(chatModel)
                .defaultAdvisors(
                        modelSupport.advisors(
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.MISTRAL,
                                        model,
                                        meterRegistry,
                                        pricingCatalog)))
                .build();
    }
}
