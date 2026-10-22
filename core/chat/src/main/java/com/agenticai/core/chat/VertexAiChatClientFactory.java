package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.VertexAccessTokenProvider;
import com.agenticai.core.providers.VertexCredential;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * {@link ProviderName#GOOGLE_VERTEX} - Vertex AI auth mode of the same unified GenAI SDK {@link
 * GeminiAgentChatClientFactory} uses for the AI Studio (API-key) path. The SDK's {@code
 * Client.Builder} routes to Vertex instead of the Developer API once {@code project}/{@code
 * location}/ {@code credentials} are supplied instead of {@code apiKey} - see that builder's own
 * field list. {@code apiKey} here is a {@link VertexCredential#encode() encoded}
 * project/location/service-account blob, not a plain secret - see that class's javadoc.
 *
 * <p><b>Prompt caching</b> is delegated to {@link VertexProviderCachingStrategy} - see that class's
 * javadoc (and {@link GeminiProviderCachingStrategy}'s, which it mirrors) for the real cache
 * lifecycle it implements and the known interface/data-flow mismatch. This factory's only remaining
 * job re: caching is building the {@link ProviderCachingContext} and calling {@link
 * ProviderCachingStrategy#apply} on the in-progress options builder.
 */
@Component
public class VertexAiChatClientFactory implements AgentChatClientFactory {

    private final VertexAccessTokenProvider tokenProvider;
    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    private final VertexProviderCachingStrategy cachingStrategy;
    private final MeterRegistry meterRegistry;
    private final LlmPricingCatalog pricingCatalog;
    // Outbound HTTP timeouts (2026-07-29 security audit) - same unbounded
    // google-genai OkHttp client as GeminiAgentChatClientFactory; see
    // LlmHttpClientFactory#readTimeout.
    private final LlmHttpClientFactory llmHttpClientFactory;
    // Shared across every factory in this package so no provider is left able
    // to have a turn killed by its own model's malformed or invented tool call -
    // see ResilientToolCallingManagerFactory.
    private final ResilientToolCallingManagerFactory toolCallingManagers;
    // Bounded retries, real ObservationRegistry, default advisors, BYOK thinking
    // bound and the shared genai Client cache - see LlmModelSupport.
    private final LlmModelSupport modelSupport;

    public VertexAiChatClientFactory(
            VertexAccessTokenProvider tokenProvider,
            LlmCallMetricsService llmCallMetricsService,
            LlmCallLogService llmCallLogService,
            VertexProviderCachingStrategy cachingStrategy,
            MeterRegistry meterRegistry,
            LlmPricingCatalog pricingCatalog,
            LlmHttpClientFactory llmHttpClientFactory,
            ResilientToolCallingManagerFactory toolCallingManagers,
            LlmModelSupport modelSupport) {
        this.modelSupport = modelSupport;
        this.tokenProvider = tokenProvider;
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
        return ProviderName.GOOGLE_VERTEX;
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
        VertexCredential credential = VertexCredential.decode(apiKey);
        // The SDK wants a com.google.auth.oauth2.GoogleCredentials, not a raw
        // bearer string - com.google.auth.oauth2.OAuth2Credentials.create()
        // wraps our already-fetched, already-cached token so we don't hand
        // the SDK the service-account key a second time just to have it
        // refresh a token we already hold.
        String accessToken = tokenProvider.accessTokenForCredential(credential);

        // Reused per (access token, project, location) rather than built per
        // turn - see LlmModelSupport#genAiClient. The token is part of the key,
        // so a refreshed token gets a fresh Client and the stale one ages out.
        Client genAiClient =
                modelSupport.genAiClient(
                        () ->
                                Client.builder()
                                        .vertexAI(true)
                                        .project(credential.projectId())
                                        .location(credential.location())
                                        .credentials(
                                                com.google.auth.oauth2.GoogleCredentials.create(
                                                        new com.google.auth.oauth2.AccessToken(
                                                                accessToken, null)))
                                        .httpOptions(
                                                HttpOptions.builder()
                                                        // Milliseconds; see
                                                        // GeminiAgentChatClientFactory for why a
                                                        // single value covers connect/read/write
                                                        // here.
                                                        .timeout(
                                                                Math.toIntExact(
                                                                        llmHttpClientFactory
                                                                                .readTimeout()
                                                                                .toMillis()))
                                                        .build())
                                        .build(),
                        "vertex-byok",
                        accessToken,
                        credential.projectId(),
                        credential.location());

        GoogleGenAiChatOptions.Builder optionsBuilder =
                GoogleGenAiChatOptions.builder()
                        .model(model)
                        .maxOutputTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP);
        sampling.applyTo(optionsBuilder);
        LlmModelSupport.logDroppedSampling(ProviderName.GOOGLE_VERTEX, model, sampling, "seed");
        // Gemini 2.5/3 think by default out of maxOutputTokens; bound it so the
        // reply is not truncated to nothing. Same mapping as the hosted pool.
        modelSupport.applyGeminiByokThinking(optionsBuilder, model);
        cachingStrategy.apply(optionsBuilder, new ProviderCachingContext(apiKey, model));
        // Provider-enforced JSON schema, only when the caller asked for one (tool-free utility
        // calls).
        GoogleGenAiChatOptions options =
                modelSupport.applyStructuredSchema(optionsBuilder.build(), providerOptions);

        // GeminiMixedContentToolCallChatModel, not GoogleGenAiChatModel.builder() -
        // shares the same google-genai SDK/client as GeminiAgentChatClientFactory, so
        // it needs the same spring-ai#5466 tool-call-dropping workaround; see that
        // class's javadoc.
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
                                        ProviderName.GOOGLE_VERTEX,
                                        model,
                                        meterRegistry,
                                        pricingCatalog)))
                .build();
    }
}
