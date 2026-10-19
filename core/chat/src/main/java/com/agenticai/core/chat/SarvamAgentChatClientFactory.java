package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Sarvam AI's {@code sarvam-105b}/
 * {@code sarvam-105b} chat models are reached the same
 * OpenAI-chat-completions-compatible way as {@link OpenRouterAgentChatClientFactory}/
 * {@link GrokAgentChatClientFactory}: reuse Spring AI's {@code
 * spring-ai-starter-model-openai} module with a fixed {@code baseUrl} instead
 * of a dedicated starter (Sarvam doesn't publish one either).
 *
 * <p><b>Auth header difference, handled deliberately:</b> unlike every other
 * OpenAI-compatible factory in this package (Bearer token via {@code
 * OpenAiApi.Builder#apiKey}), Sarvam's chat completions endpoint documents
 * two accepted auth shapes - {@code Authorization: Bearer <token>} or
 * {@code api-subscription-key: <key>} - and the build plan calls for using
 * {@code api-subscription-key} specifically, for consistency with every
 * other Sarvam endpoint Stage 3 builds (those non-chat REST clients have no
 * Bearer-token option at all). {@link NoopApiKey} tells {@code OpenAiApi} not
 * to set its own {@code Authorization} header, and the key is supplied
 * instead as a default header via {@code OpenAiApi.Builder#headers} - the
 * same "extra default headers" seam Spring AI exposes for exactly this kind
 * of non-standard-auth OpenAI-compatible provider.
 *
 * <p>Model id is the agent's free-text {@code model} field, e.g. {@code
 * "sarvam-105b"} (64K context) or {@code "sarvam-105b"} (128K context) - see
 * Sarvam's chat completion API reference.
 */
@Component
public class SarvamAgentChatClientFactory implements AgentChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(SarvamAgentChatClientFactory.class);

    // NOT "https://api.sarvam.ai/v1" - same doubling bug already fixed once for
    // OpenRouterAgentChatClientFactory/GrokAgentChatClientFactory: OpenAiApi
    // already appends its own default completionsPath ("/v1/chat/completions")
    // onto baseUrl, so a trailing "/v1" here would double up into
    // ".../v1/v1/chat/completions". Sarvam's own docs list the endpoint as
    // POST https://api.sarvam.ai/v1/chat/completions, confirming baseUrl must
    // be just the host.
    private static final String BASE_URL = "https://api.sarvam.ai";
    private static final String API_SUBSCRIPTION_KEY_HEADER = "api-subscription-key";

    // Sarvam rejects max_tokens above a per-SUBSCRIPTION-TIER ceiling, not a
    // per-model one: on the starter tier sarvam-105b allows 4096, and asking for
    // more fails the whole call with
    //   HTTP 400 "max_tokens (8192) exceeds the maximum allowed for sarvam-105b
    //             for your subscription tier (starter): 4096"
    // The agent editor's max-tokens slider goes higher than that, so a tenant can
    // save a configuration that makes every single turn fail. Observed live
    // 2026-07-29: one agent at 8192 produced 19 consecutive failed calls, which
    // surfaced to the user only as the generic "service temporarily unavailable".
    //
    // Clamping is the right trade here rather than failing: a slightly shorter
    // completion is strictly better than no completion, the ceiling is a billing
    // property we cannot discover from the model id, and the clamp is logged at
    // WARN so it is diagnosable rather than mysterious. Deployments on a higher
    // tier can raise or disable it via app.providers.sarvam.max-tokens-ceiling
    // (<= 0 disables clamping entirely).
    private static final int DEFAULT_MAX_TOKENS_CEILING = 4096;

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    // Infra audit (2026-07-27) - threaded through to LlmMetricsAdvisor, see
    // AnthropicAgentChatClientFactory's identical field comment.
    private final MeterRegistry meterRegistry;
    private final LlmPricingCatalog pricingCatalog;
    private final ResilientToolCallingManagerFactory toolCallingManagers;
    private final int maxTokensCeiling;

    // Outbound HTTP timeouts (2026-07-29 security audit) - OpenAiApi's own
    // default RestClient/WebClient have none at all. See LlmHttpClientFactory.
    private final LlmHttpClientFactory llmHttpClientFactory;
    // Bounded retries, real ObservationRegistry, default advisors, stream usage -
    // see LlmModelSupport.
    private final LlmModelSupport modelSupport;
    // Shared OpenAI-compatible transport/model construction (ADR-0012 P1b) - see OpenAiCompatModelBuilder.
    private final OpenAiCompatModelBuilder modelBuilder;

    public SarvamAgentChatClientFactory(
        LlmCallMetricsService llmCallMetricsService, LlmCallLogService llmCallLogService,
        MeterRegistry meterRegistry, LlmPricingCatalog pricingCatalog,
        ResilientToolCallingManagerFactory toolCallingManagers,
        @Value("${app.providers.sarvam.max-tokens-ceiling:" + DEFAULT_MAX_TOKENS_CEILING + "}")
        int maxTokensCeiling,
        LlmHttpClientFactory llmHttpClientFactory,
        LlmModelSupport modelSupport
    ) {
        this.modelSupport = modelSupport;
        this.llmCallMetricsService = llmCallMetricsService;
        this.llmCallLogService = llmCallLogService;
        this.meterRegistry = meterRegistry;
        this.pricingCatalog = pricingCatalog;
        this.toolCallingManagers = toolCallingManagers;
        this.modelBuilder = new OpenAiCompatModelBuilder(llmHttpClientFactory, toolCallingManagers, modelSupport);
        this.maxTokensCeiling = maxTokensCeiling;
        this.llmHttpClientFactory = llmHttpClientFactory;
    }

    /**
     * Clamps the agent's configured max tokens to what the deployment's Sarvam
     * subscription tier actually accepts. Returns the value unchanged when no
     * ceiling is configured or the request is already within it.
     */
    private int clampMaxTokens(int requested, String model) {
        if (maxTokensCeiling <= 0 || requested <= maxTokensCeiling) {
            return requested;
        }
        log.warn(
            "[SARVAM] Clamping max_tokens {} -> {} for model {}: Sarvam rejects requests above the "
                + "subscription tier ceiling with HTTP 400, which would fail every turn for this agent. "
                + "Raise app.providers.sarvam.max-tokens-ceiling if this deployment is on a higher tier.",
            requested, maxTokensCeiling, model);
        return maxTokensCeiling;
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.SARVAM;
    }

    @Override
    public ChatClient create(String apiKey, String model, int maxTokens, double temperature, double topP) {
        return create(apiKey, model, maxTokens, temperature, topP, java.util.Map.of());
    }

    /**
     * Applies the {@link SamplingOptions} Sarvam's OpenAI-compatible chat
     * completions accepts - {@code stop}, {@code seed}, {@code
     * frequency_penalty}, {@code presence_penalty}; there is no {@code top_k},
     * so it is omitted and DEBUG-logged.
     */
    @Override
    public ChatClient create(
        String apiKey, String model, int maxTokens, double temperature, double topP,
        java.util.Map<String, Object> providerOptions
    ) {
        SamplingOptions sampling = SamplingOptions.from(providerOptions);
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add(API_SUBSCRIPTION_KEY_HEADER, apiKey);

        OpenAiApi openAiApi = modelBuilder.api(BASE_URL)
            .noApiKey()
            .headers(headers)
            .build();

        OpenAiChatOptions.Builder optionsBuilder = modelBuilder.options(ProviderName.SARVAM, model)
            .maxTokens(clampMaxTokens(maxTokens, model))
            .temperature(temperature)
            .topP(topP);
        sampling.applyTo(optionsBuilder);
        LlmModelSupport.logDroppedSampling(ProviderName.SARVAM, model, sampling, "topK");
        OpenAiChatOptions options = optionsBuilder.build();

        // Sarvam is where both malformed-arguments and hallucinated-tool-name
        // turns were first observed, but neither is Sarvam-specific - see
        // ResilientToolCallingManagerFactory, which every factory in this
        // package shares (via OpenAiCompatModelBuilder).
        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport.chatClientBuilder(chatModel)
            .defaultAdvisors(modelSupport.advisors(
                new LlmMetricsAdvisor(llmCallMetricsService, llmCallLogService, ProviderName.SARVAM, model, meterRegistry, pricingCatalog)))
            .build();
    }
}
