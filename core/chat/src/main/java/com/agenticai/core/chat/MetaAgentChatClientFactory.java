package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * Meta Model API (the Muse Spark family, {@code api.meta.ai}) - added 2026-09-17, facts below
 * verified against dev.meta.ai that day. The API is OpenAI-chat-completions-compatible ({@code POST
 * /v1/chat/completions}, {@code GET /v1/models}, {@code Authorization: Bearer <key>}; keys look
 * like {@code LLM|607358788850350|...}), so - same reasoning as {@link
 * GrokAgentChatClientFactory}/{@link DeepSeekAgentChatClientFactory} - this reuses Spring AI's
 * {@code spring-ai-starter-model-openai} module with a fixed {@code baseUrl} instead of a dedicated
 * starter (Meta doesn't publish one). See {@link AnthropicAgentChatClientFactory}'s javadoc for the
 * "fresh client per call, BYOK" shape every factory in this package mirrors.
 *
 * <p>Model id is the agent's free-text {@code model} field: {@code muse-spark-1.3} (recommended,
 * the catalog default), {@code muse-spark-1.2}, {@code muse-spark-1.1}, or the {@code
 * muse-spark-1.3-contributor}/{@code muse-spark-1.2-contributor} variants - roughly a tenth of the
 * price BECAUSE META TRAINS ON THAT TRAFFIC, so the catalog labels them bluntly and nothing in this
 * codebase ever picks one as a default. All three take text, image, video, PDF/file and audio input
 * (audio degraded on 1.3) in a 1,048,576-token context, and support tool calling (parallel too),
 * structured output ({@code response_format} json_schema), streaming and prompt caching, all of
 * which Spring AI's OpenAI client already speaks. {@code muse-image-1.0} (image generation) and
 * {@code muse-voice-transcribe-1.0} (STT) live on the same API but are not chat models and are out
 * of scope here.
 *
 * <p>Request-body compatibility, per Meta's reference: {@code temperature}, {@code top_p}, {@code
 * presence_penalty}, {@code frequency_penalty}, {@code seed}, {@code max_completion_tokens} ({@code
 * max_tokens} accepted as a deprecated alias), {@code reasoning_effort} in {minimal, low, medium,
 * high, xhigh} (NOT "none"), {@code tools}, {@code response_format} and {@code n=1} are supported.
 * The API returns HTTP 400 for {@code stop}, {@code n>1}, {@code logit_bias}, {@code logprobs},
 * {@code top_logprobs}, {@code verbosity}, {@code prediction}, {@code modalities}, {@code audio}
 * and {@code web_search_options} - hence the {@code stop} strip in {@link #create}. {@code
 * LlmCallDiagnosisService} knows to read a Meta 400 that mentions {@code stop} as "unsupported
 * parameter".
 *
 * <p>Meta Model API sign-up is not open in every country yet (a key obtained anywhere works from
 * anywhere). Tenants who can't get a direct key already have Muse Spark through {@link
 * OpenRouterAgentChatClientFactory} as {@code meta/muse-spark-1.3}; that path is untouched by this
 * factory and must keep working.
 */
@Component
public class MetaAgentChatClientFactory implements AgentChatClientFactory {

    // NOT "https://api.meta.ai/v1" - see OpenRouterAgentChatClientFactory's
    // BASE_URL comment for why: Spring AI's OpenAiApi already appends its own
    // default completionsPath ("/v1/chat/completions") onto baseUrl, so a
    // trailing "/v1" here would double up into ".../v1/v1/chat/completions".
    // HostedPoolKnownEndpoints.KNOWN_PROVIDER_ENDPOINTS (hosted pool) mirrors this
    // exact value for the hosted pool's generic provider+key flow.
    private static final String BASE_URL = "https://api.meta.ai";

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

    public MetaAgentChatClientFactory(
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
        return ProviderName.META;
    }

    @Override
    public ChatClient create(
            String apiKey, String model, int maxTokens, double temperature, double topP) {
        return create(apiKey, model, maxTokens, temperature, topP, java.util.Map.of());
    }

    /**
     * Applies the {@link SamplingOptions} Meta accepts - presence/frequency penalty and seed.
     * {@code stop} is always a 400 on Meta (see the class javadoc) and there is no {@code top_k};
     * both are omitted and DEBUG-logged.
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

        OpenAiChatOptions.Builder optionsBuilder =
                modelBuilder
                        .options(ProviderName.META, model)
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP);
        if (sampling.frequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(sampling.frequencyPenalty());
        }
        if (sampling.presencePenalty() != null) {
            optionsBuilder.presencePenalty(sampling.presencePenalty());
        }
        if (sampling.seed() != null) {
            optionsBuilder.seed(sampling.seed());
        }
        LlmModelSupport.logDroppedSampling(ProviderName.META, model, sampling, "topK", "stop");
        OpenAiChatOptions options = optionsBuilder.build();
        // Meta Model API rejects any request carrying "stop" with an HTTP 400
        // (its Chat Completions reference lists stop among the always-400
        // parameters - see the class javadoc). Nothing in this factory sets
        // it, but strip defensively so a future builder change (or copied-in
        // portable ChatOptions defaults) can't silently fail every turn for a
        // Meta agent - same "sanitize what the upstream would 400 on" posture
        // as GrokAgentChatClientFactory. presence_penalty/frequency_penalty
        // ARE supported by Meta, so unlike the Grok factory they are left
        // alone here (not set, not stripped).
        options.setStopSequences(null);

        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport
                .chatClientBuilder(chatModel)
                .defaultAdvisors(
                        modelSupport.advisors(
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.META,
                                        model,
                                        meterRegistry,
                                        pricingCatalog)))
                .build();
    }
}
