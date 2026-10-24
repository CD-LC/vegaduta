package com.agenticai.core.chat;

import com.agenticai.core.providers.AzureOpenAiCredential;
import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Azure OpenAI's data-plane chat completions endpoint is OpenAI-chat-
 * completions-compatible in body shape, so this reuses Spring AI's {@code
 * spring-ai-starter-model-openai} module the same way every other factory in
 * this package does - but with two real differences from a plain OpenAI
 * call, both threaded through per-call from the {@link AzureOpenAiCredential}
 * JSON blob {@code apiKey} actually carries here (see that record's javadoc
 * for the encode/decode pattern this mirrors from {@code
 * OllamaAgentChatClientFactory}):
 *
 * <ul>
 *   <li><b>Auth header:</b> Azure uses {@code api-key: <key>}, not {@code
 *   Authorization: Bearer <key>} - same {@link NoopApiKey} + custom-default-
 *   header trick {@link SarvamAgentChatClientFactory} uses for Sarvam's own
 *   non-standard auth header.
 *   <li><b>URL shape:</b> {@code {endpoint}/openai/deployments/{deployment}/
 *   chat/completions?api-version={apiVersion}} - a per-tenant variable
 *   endpoint+deployment, not a fixed {@code BASE_URL} constant. {@code
 *   baseUrl} is set to the decoded {@code endpoint} and the deployment +
 *   api-version are baked directly into a per-call {@code completionsPath}
 *   (via {@code OpenAiApi.Builder#completionsPath}) - the deployment name is
 *   concatenated as a literal Java string, never as a {@code {placeholder}}
 *   inside the path, since {@code RestClient}'s URI templating would
 *   otherwise try to resolve a literal {@code {deploymentName}} against a
 *   template-variable array this call never supplies.
 * </ul>
 *
 * <p><b>Prompt caching</b> is delegated to {@link AzureOpenAIProviderCachingStrategy}
 * (see that class's javadoc - same routing-hint-only pattern as {@link
 * OpenAIProviderCachingStrategy}, since Azure's data plane is caching-
 * automatic server-side with {@code prompt_cache_key} as the only optional
 * consistency lever).
 */
@Component
public class AzureOpenAIAgentChatClientFactory implements AgentChatClientFactory {

    private static final String API_KEY_HEADER = "api-key";

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
    private final AzureOpenAIProviderCachingStrategy cachingStrategy;
    // Bounded retries, real ObservationRegistry, default advisors, stream usage -
    // see LlmModelSupport.
    private final LlmModelSupport modelSupport;
    // Shared OpenAI-compatible transport/model construction (ADR-0012 P1b) - see OpenAiCompatModelBuilder.
    private final OpenAiCompatModelBuilder modelBuilder;

    public AzureOpenAIAgentChatClientFactory(
        LlmCallMetricsService llmCallMetricsService, LlmCallLogService llmCallLogService,
        MeterRegistry meterRegistry, LlmPricingCatalog pricingCatalog,
        LlmHttpClientFactory llmHttpClientFactory,
        ResilientToolCallingManagerFactory toolCallingManagers,
        AzureOpenAIProviderCachingStrategy cachingStrategy,
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
        this.cachingStrategy = cachingStrategy;
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.AZURE_OPENAI;
    }

    @Override
    public ChatClient create(String apiKey, String model, int maxTokens, double temperature, double topP) {
        return create(apiKey, model, maxTokens, temperature, topP, java.util.Map.of());
    }

    /** Applies the optional {@link SamplingOptions} the provider's API supports; unsupported fields are omitted. */
    @Override
    public ChatClient create(
        String apiKey, String model, int maxTokens, double temperature, double topP,
        java.util.Map<String, Object> providerOptions
    ) {
        SamplingOptions sampling = SamplingOptions.from(providerOptions);
        AzureOpenAiCredential credential = AzureOpenAiCredential.decode(apiKey);
        // SEC-023: the endpoint is tenant-supplied - checked at USE time, and the clients below are
        // the guarded ones (blocking path address-pinned + no redirects, streaming path no redirects).
        llmHttpClientFactory.requireAllowedTenantBaseUrl(credential.endpoint());

        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add(API_KEY_HEADER, credential.apiKey());

        String completionsPath = "/openai/deployments/" + credential.deploymentName()
            + "/chat/completions?api-version=" + credential.apiVersion();

        OpenAiApi openAiApi = modelBuilder.api(credential.endpoint())
            .noApiKey()
            .headers(headers)
            .completionsPath(completionsPath)
            .guarded()
            .build();

        var optionsBuilder = modelBuilder.options(ProviderName.AZURE_OPENAI, model);
        // Azure hosts the same gpt-5*/o-series families as OpenAI, with the same
        // max_completion_tokens / no-temperature rules.
        LlmModelSupport.applyOpenAiTokensAndSampling(
            optionsBuilder, ProviderName.AZURE_OPENAI, model, maxTokens, temperature, topP, sampling);
        // Cache key is derived from the tenant's decoded Azure api-key, not the
        // outer encoded blob - see AzureOpenAiCredential.decode above.
        cachingStrategy.apply(optionsBuilder, new ProviderCachingContext(credential.apiKey(), model));
        OpenAiChatOptions options = optionsBuilder.build();

        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport.chatClientBuilder(chatModel, ProviderName.AZURE_OPENAI)
            .defaultAdvisors(modelSupport.advisors(
                new LlmMetricsAdvisor(llmCallMetricsService, llmCallLogService, ProviderName.AZURE_OPENAI, model, meterRegistry, pricingCatalog)))
            .build();
    }
}
