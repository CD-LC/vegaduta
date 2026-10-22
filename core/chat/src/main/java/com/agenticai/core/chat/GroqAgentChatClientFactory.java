package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Bonus OpenAI-compatible provider (provider fan-out, July 2026) - Groq (GroqCloud, the
 * fast-inference-cloud company - NOT {@link ProviderName#GROK}/xAI, a different company with a
 * near-identical name) publishes an OpenAI-chat-completions-compatible API, so this reuses Spring
 * AI's {@code spring-ai-starter-model-openai} module with a fixed {@code baseUrl}, the exact same
 * shape as {@link OpenRouterAgentChatClientFactory}/ {@link GrokAgentChatClientFactory} (no
 * dedicated Spring AI starter exists for Groq either).
 *
 * <p>Model id is the agent's free-text {@code model} field, e.g. {@code "llama-3.3-70b-versatile"}
 * or {@code "llama-3.1-8b-instant"} - see Groq's own model list at console.groq.com/docs/models.
 */
@Component
public class GroqAgentChatClientFactory implements AgentChatClientFactory {

    // Groq's OpenAI-compatible surface lives under "/openai", unlike most
    // other OpenAI-compatible providers in this package - baseUrl must
    // include that prefix, then OpenAiApi appends its own default
    // completionsPath ("/v1/chat/completions") on top, giving the documented
    // "https://api.groq.com/openai/v1/chat/completions".
    private static final String BASE_URL = "https://api.groq.com/openai";

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

    public GroqAgentChatClientFactory(
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
        return ProviderName.GROQ;
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
        OpenAiCompatModelBuilder.Api openAiApi = modelBuilder.api(BASE_URL).apiKey(apiKey);

        var optionsBuilder =
                modelBuilder
                        .options(ProviderName.GROQ, model)
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP);
        sampling.applyTo(optionsBuilder);
        LlmModelSupport.logDroppedSampling(ProviderName.GROQ, model, sampling, "topK");
        OpenAiChatOptions options = optionsBuilder.build();

        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport
                .chatClientBuilder(chatModel, toolCallingManagers.create())
                .defaultAdvisors(
                        modelSupport.advisors(
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.GROQ,
                                        model,
                                        meterRegistry,
                                        pricingCatalog)))
                .build();
    }
}
