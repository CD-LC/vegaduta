package com.agenticai.core.chat;

import com.agenticai.core.providers.LocalOpenAiCredential;
import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * {@link ProviderName#LOCAL_OPENAI}: a model server the tenant runs themselves that speaks the
 * OpenAI chat-completions dialect - vLLM, llama.cpp {@code llama-server}, LM Studio, Jan, LocalAI,
 * or any proxy in front of one. Built exactly like {@link OllamaAgentChatClientFactory} (Spring
 * AI's OpenAI module over the shared {@link OpenAiCompatModelBuilder}, per-tenant base URL decoded
 * from the {@link LocalOpenAiCredential} JSON blob that {@code apiKey} carries, no {@code
 * Authorization} header when no key was given), with three differences:
 *
 * <ul>
 *   <li>the base URL is whatever the server documents, usually with a {@code /v1} suffix ({@code
 *       http://localhost:8000/v1}); {@link LocalOpenAiCredential#apiRoot()} normalises it because
 *       Spring AI appends {@code /v1/chat/completions} itself;
 *   <li>calls are metered at zero cost ({@link LlmPricingCatalog#zeroCost()}): there is no
 *       per-token bill, and the unknown-model fallback rate would otherwise invent one in {@code
 *       llm_call_log} and the spend counters;
 *   <li>no Ollama-specific {@code format} handling - these servers take OpenAI {@code
 *       response_format} natively and sampling is limited to what the OpenAI surface documents
 *       ({@code stop}, {@code seed}, penalties; {@code top_k} is not part of it).
 * </ul>
 *
 * <p>SSRF: the base URL is tenant-supplied, so it is re-checked at use time and the clients are the
 * guarded ones (address-pinned blocking path, redirect-free streaming path). A private or loopback
 * server is therefore reachable only when the operator lists it in {@code
 * app.security.outbound.allowed-private-hosts}.
 */
@Component
public class LocalOpenAiAgentChatClientFactory implements AgentChatClientFactory {

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    private final MeterRegistry meterRegistry;
    // Local inference is free per token: every call is priced at an exact zero, see class javadoc.
    private final LlmPricingCatalog zeroCostPricing = LlmPricingCatalog.zeroCost();
    private final LlmHttpClientFactory llmHttpClientFactory;
    private final LlmModelSupport modelSupport;
    private final OpenAiCompatModelBuilder modelBuilder;
    private final ResilientToolCallingManagerFactory toolCallingManagers;

    public LocalOpenAiAgentChatClientFactory(
            LlmCallMetricsService llmCallMetricsService,
            LlmCallLogService llmCallLogService,
            MeterRegistry meterRegistry,
            LlmHttpClientFactory llmHttpClientFactory,
            ResilientToolCallingManagerFactory toolCallingManagers,
            LlmModelSupport modelSupport) {
        this.llmCallMetricsService = llmCallMetricsService;
        this.llmCallLogService = llmCallLogService;
        this.meterRegistry = meterRegistry;
        this.llmHttpClientFactory = llmHttpClientFactory;
        this.modelSupport = modelSupport;
        this.toolCallingManagers = toolCallingManagers;
        this.modelBuilder =
                new OpenAiCompatModelBuilder(
                        llmHttpClientFactory, toolCallingManagers, modelSupport);
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.LOCAL_OPENAI;
    }

    @Override
    public ChatClient create(
            String apiKey, String model, int maxTokens, double temperature, double topP) {
        return create(apiKey, model, maxTokens, temperature, topP, java.util.Map.of());
    }

    @Override
    public ChatClient create(
            String apiKey,
            String model,
            int maxTokens,
            double temperature,
            double topP,
            java.util.Map<String, Object> providerOptions) {
        SamplingOptions sampling = SamplingOptions.from(providerOptions);
        LocalOpenAiCredential credential = LocalOpenAiCredential.decode(apiKey);
        String root = credential.apiRoot();
        // SEC-023: checked at USE time (a row saved before a guard change, or a DNS record
        // repointed
        // since, is refused here) and the transports below are the guarded ones.
        llmHttpClientFactory.requireAllowedTenantBaseUrl(root);

        OpenAiCompatModelBuilder.Api apiBuilder = modelBuilder.api(root).guarded();
        apiBuilder =
                credential.hasApiKey()
                        ? apiBuilder.apiKey(credential.apiKey().trim())
                        : apiBuilder.noApiKey();

        OpenAiChatOptions.Builder optionsBuilder =
                modelBuilder
                        .options(ProviderName.LOCAL_OPENAI, model)
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP);
        sampling.applyTo(optionsBuilder);
        LlmModelSupport.logDroppedSampling(ProviderName.LOCAL_OPENAI, model, sampling, "topK");
        OpenAiChatModel chatModel = modelBuilder.chatModel(apiBuilder, optionsBuilder.build());

        return modelSupport
                .chatClientBuilder(chatModel, toolCallingManagers.create())
                .defaultAdvisors(
                        modelSupport.advisors(
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.LOCAL_OPENAI,
                                        model,
                                        meterRegistry,
                                        zeroCostPricing)))
                .build();
    }
}
