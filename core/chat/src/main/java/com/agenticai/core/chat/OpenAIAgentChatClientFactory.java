package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * Real OpenAI (api.openai.com) - the one provider {@code ProvidersPanel.js} has always shown as a
 * selectable card (it's {@code PROVIDERS[1]}, right after Anthropic) that never actually worked:
 * {@code IMPLEMENTED_PROVIDERS} omitted it, so activating a key 404'd with "OPENAI doesn't have a
 * working validator yet." Same shape as {@link OpenRouterAgentChatClientFactory}/ {@link
 * GrokAgentChatClientFactory} (both already reuse Spring AI's {@code
 * spring-ai-starter-model-openai} module for OpenAI-chat-completions-compatible APIs) - this is the
 * trivial case of that pattern, since OpenAI's API *is* the OpenAI chat-completions API, so {@link
 * OpenAiApi.Builder} needs no {@code baseUrl} override at all (its own default is already {@code
 * https://api.openai.com}); set explicitly below anyway to keep this factory visually consistent
 * with its OpenAI-compatible siblings rather than relying on an implicit default a future reader
 * would have to go check.
 *
 * <p>Model id is the agent's free-text {@code model} field, e.g. {@code "gpt-4o"} or {@code
 * "gpt-4o-mini"} - see OpenAI's own model list at platform.openai.com/docs/models. {@code
 * OpenAiChatAutoConfiguration} is already excluded in {@code CoreApplication} (it was excluded from
 * the very first OpenAI-compatible provider added, precisely so a dedicated, per-tenant-BYOK
 * factory like this one could exist instead of Spring AI's single static-key autoconfigured bean) -
 * see {@code AnthropicAgentChatClientFactory}'s javadoc for the full "fresh client per call, BYOK"
 * reasoning this mirrors.
 *
 * <p><b>Prompt caching</b> is no longer inline here - it's delegated to {@link
 * OpenAIProviderCachingStrategy}, the {@link ProviderCachingStrategy} implementation for this
 * provider (see that class's javadoc for why OpenAI's shape is a single routing hint rather than
 * the cache-object lifecycle Anthropic needs). This factory's only remaining job re: caching is
 * building the {@link ProviderCachingContext} and calling {@link ProviderCachingStrategy#apply} on
 * the in-progress options builder.
 */
@Component
public class OpenAIAgentChatClientFactory implements AgentChatClientFactory {

    private static final String BASE_URL = "https://api.openai.com";

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    // Infra audit (2026-07-27) - threaded through to LlmMetricsAdvisor, see
    // AnthropicAgentChatClientFactory's identical field comment.
    private final MeterRegistry meterRegistry;
    private final LlmPricingCatalog pricingCatalog;
    // Outbound HTTP timeouts (2026-07-29 security audit) - OpenAiApi's own
    // default RestClient/WebClient have none at all. See LlmHttpClientFactory.
    private final LlmHttpClientFactory llmHttpClientFactory;
    // Shared across every factory in this package so no provider is left able
    // to have a turn killed by its own model's malformed or invented tool call -
    // see ResilientToolCallingManagerFactory.
    private final ResilientToolCallingManagerFactory toolCallingManagers;
    // See class javadoc's "Prompt caching" section.
    private final OpenAIProviderCachingStrategy cachingStrategy;
    // Bounded retries, real ObservationRegistry, default advisors, stream usage -
    // see LlmModelSupport.
    private final LlmModelSupport modelSupport;
    // Shared OpenAI-compatible transport/model construction (ADR-0012 P1b) - see
    // OpenAiCompatModelBuilder.
    private final OpenAiCompatModelBuilder modelBuilder;

    public OpenAIAgentChatClientFactory(
            LlmCallMetricsService llmCallMetricsService,
            LlmCallLogService llmCallLogService,
            MeterRegistry meterRegistry,
            LlmPricingCatalog pricingCatalog,
            LlmHttpClientFactory llmHttpClientFactory,
            ResilientToolCallingManagerFactory toolCallingManagers,
            OpenAIProviderCachingStrategy cachingStrategy,
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
        return ProviderName.OPENAI;
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
        OpenAiApi openAiApi =
                modelBuilder
                        .api(BASE_URL)
                        .apiKey(apiKey)
                        // withToolStrict is the identity unless app.llm.tools.strict.enabled
                        // (OpenAI strict function calling).
                        .restClientBuilder(
                                modelSupport.withToolStrict(modelBuilder.restClientBuilder()))
                        .webClientBuilder(
                                modelSupport.withToolStrict(modelBuilder.webClientBuilder()))
                        .build();

        var optionsBuilder = modelBuilder.options(ProviderName.OPENAI, model);
        // gpt-5*/o-series need max_completion_tokens and reject temperature/top_p.
        LlmModelSupport.applyOpenAiTokensAndSampling(
                optionsBuilder, ProviderName.OPENAI, model, maxTokens, temperature, topP, sampling);
        cachingStrategy.apply(optionsBuilder, new ProviderCachingContext(apiKey, model));
        // Provider-enforced JSON schema, only when the caller asked for one (tool-free utility
        // calls).
        OpenAiChatOptions options =
                modelSupport.applyStructuredSchema(optionsBuilder.build(), providerOptions);

        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport
                .chatClientBuilder(chatModel)
                .defaultAdvisors(
                        modelSupport.advisors(
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.OPENAI,
                                        model,
                                        meterRegistry,
                                        pricingCatalog)))
                .build();
    }
}
