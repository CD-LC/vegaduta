package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * "implement openrouter, grok and gemini so that consumers can use them" - OpenRouter
 * (openrouter.ai) is a routing gateway in front of dozens of models from many labs, but its own API
 * is OpenAI-chat-completions- compatible, so this reuses Spring AI's {@code
 * spring-ai-starter-model-openai} module ({@code OpenAiApi}/{@link OpenAiChatModel}) rather than a
 * dedicated starter (none exists) - only {@code baseUrl} differs from a real OpenAI call. Same
 * "fresh client per call, BYOK, no static application.yml key" shape as {@link
 * AnthropicAgentChatClientFactory} - see that class's javadoc for the reasoning this mirrors,
 * including why {@code OpenAiChatAutoConfiguration} is excluded in {@code CoreApplication}.
 *
 * <p>An agent's {@code model} field is a free-text string for every provider (see {@code
 * AgentsPanel.js}'s model input) - for OpenRouter this is the OpenRouter model id, e.g. {@code
 * "anthropic/claude-sonnet-4.5"} or {@code "x-ai/grok-4"} (OpenRouter also proxies Grok - {@link
 * GrokAgentChatClientFactory} is for calling xAI directly with an xAI key instead).
 */
@Component
public class OpenRouterAgentChatClientFactory implements AgentChatClientFactory {

    // NOT "https://openrouter.ai/api/v1" - Spring AI's OpenAiApi already appends
    // its own default completionsPath ("/v1/chat/completions") onto baseUrl, so
    // including "/v1" here doubled it up into ".../api/v1/v1/chat/completions"
    // (confirmed 404 in production: "POST .../api/v1/v1/chat/completions").
    // baseUrl must be just the host+/api prefix, letting OpenAiApi supply the
    // "/v1" itself - same fix applies to GrokAgentChatClientFactory.
    private static final String BASE_URL = "https://openrouter.ai/api";

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    // Infra audit (2026-07-27) - threaded through to LlmMetricsAdvisor, see
    // AnthropicAgentChatClientFactory's identical field comment.
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

    public OpenRouterAgentChatClientFactory(
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
        return ProviderName.OPENROUTER;
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

        var optionsBuilder = modelBuilder.options(ProviderName.OPENROUTER, model);
        // "openai/gpt-5*" / "openai/o*" ids are OpenAI reasoning models behind
        // OpenRouter and carry the same max_completion_tokens / no-temperature rules.
        LlmModelSupport.applyOpenAiTokensAndSampling(
                optionsBuilder,
                ProviderName.OPENROUTER,
                model,
                maxTokens,
                temperature,
                topP,
                sampling);
        OpenAiChatOptions options = optionsBuilder.build();

        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport
                .chatClientBuilder(chatModel, toolCallingManagers.create())
                .defaultAdvisors(
                        modelSupport.advisors(
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.OPENROUTER,
                                        model,
                                        meterRegistry,
                                        pricingCatalog)))
                .build();
    }
}
