package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * "implement openrouter, grok and gemini so that consumers can use them" - direct Google AI Studio
 * (Gemini Developer API) key auth via Google's unified GenAI Java SDK ({@code
 * com.google.genai.Client}), NOT {@link ProviderName#GOOGLE_VERTEX} (GCP service-account auth,
 * still unimplemented) - {@code Client.builder().apiKey(...)} alone (no {@code project}/{@code
 * location}) is exactly what routes the same underlying SDK to the Gemini Developer API instead of
 * Vertex AI, per Spring AI's own google-genai reference docs. Chosen over Vertex specifically
 * because it is the BYOK-friendly path this platform's whole provider model already assumes (see
 * {@code ProviderCredentialResolver}) - a tenant pastes an API key from aistudio.google.com, the
 * same shape as every other provider here, rather than uploading a GCP service-account JSON.
 *
 * <p>Same "fresh client per call, no static application.yml key" shape as {@link
 * AnthropicAgentChatClientFactory} - see that class's javadoc for the full reasoning. {@code
 * GoogleGenAiChatAutoConfiguration} is excluded in {@code CoreApplication} for the same reason
 * {@code AnthropicChatAutoConfiguration} is.
 *
 * <p>Model id is the agent's free-text {@code model} field, e.g. {@code "gemini-2.5-flash"} or
 * {@code "gemini-2.5-pro"}.
 *
 * <p><b>Prompt caching</b> is delegated to {@link GeminiProviderCachingStrategy}, the {@link
 * ProviderCachingStrategy} implementation for this provider - see that class's javadoc for the real
 * cache lifecycle it implements AND the known interface/data-flow mismatch (it can only ever reuse
 * an already-live cache from this call site, never create one). This factory's only remaining job
 * re: caching is building the {@link ProviderCachingContext} and calling {@link
 * ProviderCachingStrategy#apply} on the in-progress options builder.
 */
@Component
public class GeminiAgentChatClientFactory implements AgentChatClientFactory {

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    private final GeminiProviderCachingStrategy cachingStrategy;
    // Infra audit (2026-07-27) - threaded through to LlmMetricsAdvisor, see
    // AnthropicAgentChatClientFactory's identical field comment.
    private final MeterRegistry meterRegistry;
    private final LlmPricingCatalog pricingCatalog;
    // Outbound HTTP timeouts (2026-07-29 security audit). google-genai zeroes
    // OkHttp's connect/read/write timeouts and only re-applies them from
    // HttpOptions, so with HttpOptions unset this path was fully unbounded -
    // see LlmHttpClientFactory#readTimeout.
    private final LlmHttpClientFactory llmHttpClientFactory;
    // Shared across every factory in this package so no provider is left able
    // to have a turn killed by its own model's malformed or invented tool call -
    // see ResilientToolCallingManagerFactory.
    private final ResilientToolCallingManagerFactory toolCallingManagers;
    // Bounded retries, real ObservationRegistry, default advisors, BYOK thinking
    // bound and the shared genai Client cache - see LlmModelSupport.
    private final LlmModelSupport modelSupport;

    public GeminiAgentChatClientFactory(
            LlmCallMetricsService llmCallMetricsService,
            LlmCallLogService llmCallLogService,
            GeminiProviderCachingStrategy cachingStrategy,
            MeterRegistry meterRegistry,
            LlmPricingCatalog pricingCatalog,
            LlmHttpClientFactory llmHttpClientFactory,
            ResilientToolCallingManagerFactory toolCallingManagers,
            LlmModelSupport modelSupport) {
        this.modelSupport = modelSupport;
        this.llmCallMetricsService = llmCallMetricsService;
        this.llmCallLogService = llmCallLogService;
        this.cachingStrategy = cachingStrategy;
        this.meterRegistry = meterRegistry;
        this.pricingCatalog = pricingCatalog;
        this.llmHttpClientFactory = llmHttpClientFactory;
        this.toolCallingManagers = toolCallingManagers;
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.GEMINI;
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
        // Reused per API key rather than built per turn: each Client owns an
        // OkHttp pool + dispatcher that was never closed. See LlmModelSupport#genAiClient.
        Client genAiClient =
                modelSupport.genAiClient(
                        () ->
                                Client.builder()
                                        .apiKey(apiKey)
                                        .httpOptions(
                                                HttpOptions.builder()
                                                        // Milliseconds; OkHttp's read timeout is
                                                        // per-read (socket
                                                        // idle), not a cap on total response time,
                                                        // so this does not
                                                        // truncate a long streamed generation.
                                                        .timeout(
                                                                Math.toIntExact(
                                                                        llmHttpClientFactory
                                                                                .readTimeout()
                                                                                .toMillis()))
                                                        .build())
                                        .build(),
                        "gemini-developer-api",
                        apiKey);

        GoogleGenAiChatOptions.Builder optionsBuilder =
                GoogleGenAiChatOptions.builder()
                        .model(model)
                        .maxOutputTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP);
        sampling.applyTo(optionsBuilder);
        LlmModelSupport.logDroppedSampling(ProviderName.GEMINI, model, sampling, "seed");
        // Gemini 2.5/3 think by default out of maxOutputTokens; bound it so the
        // reply is not truncated to nothing. Same mapping as the hosted pool.
        modelSupport.applyGeminiByokThinking(optionsBuilder, model);
        cachingStrategy.apply(optionsBuilder, new ProviderCachingContext(apiKey, model));
        // Provider-enforced JSON schema, only when the caller asked for one (tool-free utility
        // calls).
        GoogleGenAiChatOptions options =
                modelSupport.applyStructuredSchema(optionsBuilder.build(), providerOptions);

        // GeminiMixedContentToolCallChatModel, not GoogleGenAiChatModel.builder() -
        // see that class's javadoc for the spring-ai#5466 tool-call-dropping bug
        // it works around.
        GoogleGenAiChatModel chatModel =
                new GeminiMixedContentToolCallChatModel(
                        genAiClient,
                        options,
                        toolCallingManagers.create(),
                        modelSupport.retry(),
                        modelSupport.observationRegistry());

        return modelSupport
                .chatClientBuilder(chatModel, toolCallingManagers.create())
                .defaultAdvisors(
                        modelSupport.advisors(
                                // Gemini rejects >1 system message; see
                                // SystemMessageMergingAdvisor's javadoc.
                                new SystemMessageMergingAdvisor(),
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.GEMINI,
                                        model,
                                        meterRegistry,
                                        pricingCatalog)))
                .build();
    }
}
