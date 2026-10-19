package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Constructs a fresh AnthropicApi + AnthropicChatModel + ChatClient per call
 * from the caller-supplied key - deliberately not a singleton bean built
 * from a static application.yml key, since this platform is BYOK (see the
 * exclusion of AnthropicChatAutoConfiguration in CoreApplication).
 *
 * <p>Spring AI 1.1.x removed the simple `new AnthropicApi(key)` constructor
 * in favor of a builder + typed ApiKey - AnthropicApi.builder().apiKey(String)
 * still accepts a raw string and wraps it as a SimpleApiKey internally.
 *
 * <p>Known cost: reconstructs the client on every chat call rather than
 * caching per credential. Fine for MVP correctness; worth caching
 * (keyed by credential id + updatedAt, invalidated on key rotation) once
 * this is under real load - noted for V1 hardening, not done here to avoid
 * a caching bug masquerading as a feature this early.
 *
 * <p>Every {@link ChatClient} built here also gets {@link LlmMetricsAdvisor}
 * registered as a default advisor - direct feedback on Platform Maintenance:
 * "capture any metrics like latency, how many LLM calls were made." Since
 * this factory is the sole construction point for every ChatClient in the
 * app (see {@link ChatClientRegistry}), one line here covers every real call
 * site with no per-site instrumentation needed.
 *
 * <p><b>Prompt caching</b> (Anthropic docs:
 * platform.claude.com/docs/en/build-with-claude/prompt-caching) is no longer
 * inline here - it's delegated to {@link AnthropicProviderCachingStrategy},
 * the {@link ProviderCachingStrategy} implementation for this provider (see
 * that class's javadoc for the strategy/TTL reasoning this factory used to
 * carry directly). This factory's only remaining job re: caching is building
 * the {@link ProviderCachingContext} and calling {@link
 * ProviderCachingStrategy#apply} on the in-progress options builder.
 */
@Component
public class AnthropicAgentChatClientFactory implements AgentChatClientFactory {

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    private final AnthropicProviderCachingStrategy cachingStrategy;
    // Shared model policy: bounded retries, the real ObservationRegistry, and the
    // DefaultAgentAdvisor beans (e.g. core/rl's RlTrajectoryAdvisor) this factory
    // used to be the only one to attach - see LlmModelSupport.
    private final LlmModelSupport modelSupport;
    // Infra audit (2026-07-27) - threaded straight through to LlmMetricsAdvisor
    // (see that class's javadoc for what it now records); both are ordinary
    // Spring-autoconfigured/-managed beans, held here for the same reason
    // llmCallMetricsService/llmCallLogService already are.
    private final MeterRegistry meterRegistry;
    private final LlmPricingCatalog pricingCatalog;
    // Outbound HTTP timeouts (2026-07-29 security audit) - AnthropicApi's own
    // default RestClient/WebClient have none at all. See LlmHttpClientFactory.
    private final LlmHttpClientFactory llmHttpClientFactory;
    // Shared across every factory in this package so no provider is left able
    // to have a turn killed by its own model's malformed or invented tool call -
    // see ResilientToolCallingManagerFactory.
    private final ResilientToolCallingManagerFactory toolCallingManagers;
    // Shared Anthropic transport/model construction (ADR-0012 P1b) - see AnthropicModelBuilder.
    private final AnthropicModelBuilder modelBuilder;
    // Extended-thinking mapping (see AnthropicThinking). Kill-switch + default effort;
    // blank effort = thinking off, which is the pre-existing behaviour.
    private final boolean thinkingEnabled;
    private final String thinkingEffort;

    public AnthropicAgentChatClientFactory(
        LlmCallMetricsService llmCallMetricsService,
        LlmCallLogService llmCallLogService,
        LlmModelSupport modelSupport,
        AnthropicProviderCachingStrategy cachingStrategy,
        MeterRegistry meterRegistry,
        LlmPricingCatalog pricingCatalog,
        LlmHttpClientFactory llmHttpClientFactory,
        ResilientToolCallingManagerFactory toolCallingManagers,
        @Value("${app.llm.anthropic.thinking.enabled:true}") boolean thinkingEnabled,
        @Value("${app.llm.anthropic.thinking.effort:}") String thinkingEffort
    ) {
        this.llmCallMetricsService = llmCallMetricsService;
        this.llmCallLogService = llmCallLogService;
        this.modelSupport = modelSupport;
        this.cachingStrategy = cachingStrategy;
        this.meterRegistry = meterRegistry;
        this.pricingCatalog = pricingCatalog;
        this.llmHttpClientFactory = llmHttpClientFactory;
        this.toolCallingManagers = toolCallingManagers;
        this.modelBuilder = new AnthropicModelBuilder(llmHttpClientFactory, toolCallingManagers, modelSupport);
        this.thinkingEnabled = thinkingEnabled;
        this.thinkingEffort = thinkingEffort;
    }

    // Models where Anthropic's Messages API rejects the `temperature`
    // sampling param outright ("`temperature` is deprecated for this
    // model", HTTP 400) - confirmed for real against claude-sonnet-5 during
    // Stage 1 RIGOROUS-verifier testing (July 2026). Anthropic doesn't
    // expose this via any queryable model-capability field today, so this
    // is a hand-maintained prefix list rather than something derived from
    // the API - if the same error surfaces for another model id, add its
    // prefix here.
    private static final List<String> TEMPERATURE_DEPRECATED_MODEL_PREFIXES = List.of(
        "claude-sonnet-5"
    );

    private static boolean temperatureSupported(String model) {
        if (model == null) {
            return true;
        }
        String normalized = model.toLowerCase(Locale.ROOT);
        return TEMPERATURE_DEPRECATED_MODEL_PREFIXES.stream().noneMatch(normalized::startsWith);
    }

    /**
     * Builds the model/token/sampling part of the options. With thinking ON, Anthropic rejects
     * temperature, top_p AND top_k (400), so none of them are sent - only stop sequences from
     * {@link SamplingOptions} survive. With thinking off this is the pre-existing behaviour.
     */
    static AnthropicChatOptions.Builder samplingBuilder(
        String model, int maxTokens, double temperature, double topP, SamplingOptions sampling, Integer thinkingBudget
    ) {
        var optionsBuilder = AnthropicChatOptions.builder()
            .model(model)
            .maxTokens(maxTokens);
        if (thinkingBudget != null) {
            optionsBuilder.thinking(AnthropicApi.ThinkingType.ENABLED, thinkingBudget);
            if (!sampling.stopSequences().isEmpty()) {
                optionsBuilder.stopSequences(sampling.stopSequences());
            }
            return optionsBuilder;
        }
        if (topP != 1.0) {
            optionsBuilder.topP(topP);
        } else if (temperatureSupported(model)) {
            optionsBuilder.temperature(temperature);
        }
        sampling.applyTo(optionsBuilder);
        return optionsBuilder;
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.ANTHROPIC;
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
        AnthropicApi anthropicApi = modelBuilder.api(apiKey);

        // Anthropic's Messages API rejects a request that sets BOTH
        // temperature and top_p ("`temperature` and `top_p` cannot both be
        // specified for this model. Please use only one.") - unlike OpenAI,
        // which silently accepts both. topP's "not deliberately configured"
        // sentinel is 1.0 across this codebase (see Agent's own field
        // comment: "1.0 = no narrowing, the provider's own default"), so:
        // send topP only when a caller has actually narrowed it away from
        // 1.0 (an agent whose admin tuned it in the console), otherwise send
        // temperature - never both, regardless of caller.
        //
        // On top of that: some models (see
        // TEMPERATURE_DEPRECATED_MODEL_PREFIXES above) reject `temperature`
        // even alone. For those, if the caller hasn't narrowed topP either,
        // omit both sampling params entirely and let Anthropic apply its
        // own default rather than sending a value it will reject.
        // Extended thinking: effort -> budget_tokens (null = off, the default).
        Integer thinkingBudget = thinkingEnabled
            ? AnthropicThinking.budgetFor(model, AnthropicThinking.resolveEffort(providerOptions, thinkingEffort), maxTokens)
            : null;
        var optionsBuilder = samplingBuilder(model, maxTokens, temperature, topP, sampling, thinkingBudget);
        cachingStrategy.apply(optionsBuilder, new ProviderCachingContext(apiKey, model));
        AnthropicChatOptions options = optionsBuilder.build();

        AnthropicChatModel baseModel = modelBuilder.chatModel(anthropicApi, options);
        org.springframework.ai.chat.model.ChatModel chatModel = baseModel;
        if (thinkingBudget != null) {
            // Thinking on: guard against the 1.1.7 defects (reply = reasoning text, thinking not
            // replayed in tool loops). Tool-carrying requests go to a thinking-off sibling model.
            var plainBuilder = samplingBuilder(model, maxTokens, temperature, topP, sampling, null);
            cachingStrategy.apply(plainBuilder, new ProviderCachingContext(apiKey, model));
            chatModel = new AnthropicThinkingChatModel(baseModel, modelBuilder.chatModel(anthropicApi, plainBuilder.build()));
        }
        // Native structured output by forced tool use: only when the caller asked for a schema AND
        // app.llm.structured-output.anthropic-tool-forcing.enabled is on; with thinking on it is
        // skipped (forced tool_choice + thinking = 400). Otherwise returns chatModel unchanged.
        chatModel = modelSupport.applyAnthropicStructuredSchema(chatModel, model, thinkingBudget != null, providerOptions);

        // LlmMetricsAdvisor is per-call (carries this model's provider/model),
        // so it's constructed fresh here; the DefaultAgentAdvisor beans are
        // shared singletons appended after it (order 0 vs 100+, see each).
        return modelSupport.chatClientBuilder(chatModel)
            .defaultAdvisors(modelSupport.advisors(
                new LlmMetricsAdvisor(llmCallMetricsService, llmCallLogService, ProviderName.ANTHROPIC, model, meterRegistry, pricingCatalog)))
            .build();
    }
}
