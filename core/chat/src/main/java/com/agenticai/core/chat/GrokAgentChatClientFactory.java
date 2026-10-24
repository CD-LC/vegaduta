package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * "implement openrouter, grok and gemini so that consumers can use them" -
 * xAI's Grok API (api.x.ai) is OpenAI-chat-completions-compatible, so - same
 * reasoning as {@link OpenRouterAgentChatClientFactory} - this reuses Spring
 * AI's {@code spring-ai-starter-model-openai} module with a fixed {@code
 * baseUrl} instead of a dedicated starter (xAI doesn't publish one). See
 * {@link AnthropicAgentChatClientFactory}'s javadoc for the "fresh client per
 * call, BYOK" shape both of these OpenAI-compatible factories mirror.
 *
 * <p>Model id is the agent's free-text {@code model} field, e.g. {@code
 * "grok-4.5"} or {@code "grok-4.20-0309-non-reasoning"} - see xAI's own
 * model list at docs.x.ai. (The whole grok-3.x/4.0 line was retired
 * 2026-05-15 - see {@code ProviderModelCatalogService}'s GROK seed comment.)
 */
@Component
public class GrokAgentChatClientFactory implements AgentChatClientFactory {

    // NOT "https://api.x.ai/v1" - see OpenRouterAgentChatClientFactory's BASE_URL
    // comment for why: Spring AI's OpenAiApi already appends its own default
    // completionsPath ("/v1/chat/completions") onto baseUrl, so a trailing "/v1"
    // here would double up into ".../v1/v1/chat/completions".
    private static final String BASE_URL = "https://api.x.ai";

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
    // Shared OpenAI-compatible transport/model construction (ADR-0012 P1b) - see OpenAiCompatModelBuilder.
    private final OpenAiCompatModelBuilder modelBuilder;

    public GrokAgentChatClientFactory(
        LlmCallMetricsService llmCallMetricsService, LlmCallLogService llmCallLogService,
        MeterRegistry meterRegistry, LlmPricingCatalog pricingCatalog,
        LlmHttpClientFactory llmHttpClientFactory,
        ResilientToolCallingManagerFactory toolCallingManagers,
        LlmModelSupport modelSupport
    ) {
        this.modelSupport = modelSupport;
        this.llmCallMetricsService = llmCallMetricsService;
        this.llmCallLogService = llmCallLogService;
        this.meterRegistry = meterRegistry;
        this.pricingCatalog = pricingCatalog;
        this.llmHttpClientFactory = llmHttpClientFactory;
        this.toolCallingManagers = toolCallingManagers;
        this.modelBuilder = new OpenAiCompatModelBuilder(llmHttpClientFactory, toolCallingManagers, modelSupport);
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.GROK;
    }

    @Override
    public ChatClient create(String apiKey, String model, int maxTokens, double temperature, double topP) {
        return create(apiKey, model, maxTokens, temperature, topP, java.util.Map.of());
    }

    /**
     * xAI Agent Tools API (2026-09-23, {@link XaiServerToolSupport}) - when {@code
     * providerOptions} carries a non-empty {@code xaiServerTools} set (the per-agent
     * {@code xaiServerToolsEnabled} opt-in, threaded by {@code AgentChatService}), the
     * request serializer on BOTH transports appends {@code {"type":"web_search"}} /
     * {@code "x_search"} / {@code "code_execution"} entries to the outgoing {@code tools}
     * array so xAI executes them server-side. An empty/absent set produces a byte-identical
     * client to before - every other {@code AgentChatClientFactory}'s default overload
     * ignores the map, so this is the only factory that reads it.
     */
    @Override
    public ChatClient create(
        String apiKey, String model, int maxTokens, double temperature, double topP,
        java.util.Map<String, Object> providerOptions
    ) {
        java.util.Set<String> serverTools = XaiServerToolSupport.enabledTools(providerOptions);
        org.springframework.web.client.RestClient.Builder restClientBuilder = modelBuilder.restClientBuilder();
        org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder = modelBuilder.webClientBuilder();
        if (!serverTools.isEmpty()) {
            restClientBuilder.messageConverters(converters ->
                converters.add(0, XaiServerToolSupport.messageConverter(serverTools)));
            webClientBuilder.codecs(codecs ->
                codecs.defaultCodecs().jackson2JsonEncoder(XaiServerToolSupport.jsonEncoder(serverTools)));
        }
        OpenAiApi openAiApi = modelBuilder.api(BASE_URL)
            .apiKey(apiKey)
            .restClientBuilder(restClientBuilder)
            .webClientBuilder(webClientBuilder)
            .build();

        OpenAiChatOptions options = modelBuilder.options(ProviderName.GROK, model)
            .maxTokens(maxTokens)
            .temperature(temperature)
            .topP(topP)
            .build();
        // Per-agent SamplingOptions are deliberately NOT applied (see the strip
        // below); say so at DEBUG rather than dropping them invisibly.
        LlmModelSupport.logDroppedSampling(ProviderName.GROK, model, SamplingOptions.from(providerOptions),
            "topK", "stop", "frequencyPenalty", "presencePenalty", "seed");
        // Grok 4.x reasoning models reject requests that carry stop,
        // presence_penalty or frequency_penalty with an HTTP 400 (xAI's API
        // reference: "not supported by reasoning models"). Nothing in this
        // factory sets them, but strip defensively anyway so a future
        // builder change (or copied-in portable ChatOptions defaults) can't
        // silently fail every turn for a Grok agent - same "clamp/sanitize
        // what the upstream would 400 on" posture as
        // SarvamAgentChatClientFactory's max-tokens ceiling.
        options.setStopSequences(null);
        options.setPresencePenalty(null);
        options.setFrequencyPenalty(null);

        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport.chatClientBuilder(chatModel, ProviderName.GROK)
            .defaultAdvisors(modelSupport.advisors(
                new LlmMetricsAdvisor(llmCallMetricsService, llmCallLogService, ProviderName.GROK, model, meterRegistry, pricingCatalog)))
            .build();
    }
}
