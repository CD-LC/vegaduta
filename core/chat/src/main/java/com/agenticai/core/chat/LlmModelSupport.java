package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * The cross-cutting model-construction policy every chat-client factory in this package shares, so
 * that no single provider can drift from it again. Before this class each factory built its Spring
 * AI {@code ChatModel} with whatever that builder defaulted to, and the defaults were wrong for
 * this platform in four separate ways (2026-09-28 Spring AI audit):
 *
 * <ul>
 *   <li><b>Retries.</b> No factory set a {@code RetryTemplate}, so Spring AI's {@code
 *       RetryUtils.DEFAULT_RETRY_TEMPLATE} applied: 10 attempts with exponential backoff up to 3
 *       minutes, on top of a 10-minute read timeout ({@link LlmHttpClientFactory}). One failing
 *       call could block a turn for tens of minutes - and all of it ran BEFORE the caller's own
 *       provider fallback got a chance. {@link #retryTemplate()} is bounded instead: few attempts,
 *       short capped backoff, transient errors only, and never a retry after a read timeout (that
 *       would just double a wait that already hit the ceiling).
 *   <li><b>Observability.</b> Every model and {@code ChatClient} ran on {@link
 *       ObservationRegistry#NOOP} (explicitly for Gemini/Vertex, implicitly for the rest), so none
 *       of Spring AI's own chat/advisor observations reached the app's registry. {@link
 *       #chatClientBuilder} and {@link #observationRegistry()} carry the real one.
 *   <li><b>Default advisors.</b> Only the Anthropic factory attached the {@link
 *       DefaultAgentAdvisor} beans (e.g. core/rl's trajectory advisor), so every other provider's
 *       turns were invisible to them. {@link #advisors} gives every factory the same list.
 *   <li><b>Streaming usage.</b> OpenAI-compatible endpoints only report token usage on a stream
 *       when asked ({@code stream_options.include_usage}), so FAST-depth turns reached budgets and
 *       cost accounting as 0 tokens. {@link #streamUsage} decides per provider.
 * </ul>
 *
 * <p>It also owns the shared google-genai {@link Client} cache (see {@link #genAiClient}) and the
 * per-model-family option helpers that more than one factory needs ({@link
 * #applyOpenAiTokensAndSampling}, {@link #applyGeminiByokThinking}).
 */
@Component
public class LlmModelSupport implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LlmModelSupport.class);

    private final LlmRetry retry;
    private final ObservationRegistry observationRegistry;
    // Resolved lazily at each create(): DefaultAgentAdvisor beans live in other
    // modules (core/rl) whose own graphs may reach back to the chat factories,
    // so eager List injection here would be one refactor away from a cycle.
    private final ObjectProvider<DefaultAgentAdvisor> defaultAgentAdvisors;
    private final Set<ProviderName> streamUsageDisabled;
    private final String geminiByokReasoningEffort;
    private final boolean nativeStructuredOutputs;
    private final GenAiClientCache genAiClients;

    // app.observability.genai-attrs = legacy | new | both (OTel gen_ai.system ->
    // gen_ai.provider.name
    // rename; see GenAiAttributeNames). Field-injected, not a constructor argument, so the many
    // hand-built test instances keep their signature and default to "both".
    @Value("${app.observability.genai-attrs:both}")
    private String genAiAttrs = "both";

    // Wave E (structured lane) opt-ins. All default false = request/behaviour identical to before.
    // Field-injected for the same reason as genAiAttrs above (hand-built test instances).
    /**
     * Anthropic native structured output via forced tool use (see
     * AnthropicStructuredToolChatModel).
     */
    @Value("${app.llm.structured-output.anthropic-tool-forcing.enabled:false}")
    boolean anthropicToolForcing = false;

    /** Ollama {@code response_format: json_schema} (its {@code format} parameter). */
    @Value("${app.llm.structured-output.ollama-format.enabled:false}")
    boolean ollamaFormat = false;

    /** OpenAI {@code strict: true} on function tools whose schema satisfies the strict subset. */
    @Value("${app.llm.tools.strict.enabled:false}")
    boolean strictTools = false;

    // app.observability.langfuse.user-id-mode = user | user-hash | tenant (see LangfuseUserId);
    // default user = the real Keycloak subject, as before. The salt is a deployment secret.
    @Value("${app.observability.langfuse.user-id-mode:user}")
    private String langfuseUserIdMode = "user";

    @Value("${app.observability.langfuse.user-id-salt:}")
    private String langfuseUserIdSalt = "";

    public LlmModelSupport(
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<DefaultAgentAdvisor> defaultAgentAdvisors,
            @Value("${app.llm.retry.max-attempts:2}") int retryMaxAttempts,
            @Value("${app.llm.retry.initial-backoff:1s}") Duration retryInitialBackoff,
            @Value("${app.llm.retry.max-backoff:10s}") Duration retryMaxBackoff,
            @Value("${app.llm.observations.enabled:true}") boolean observationsEnabled,
            // Mistral is off by default: its API documents no stream_options field
            // and validates request bodies strictly, so asking could 4xx every
            // streamed turn. Flip it on by overriding this list once verified.
            @Value("${app.llm.stream-usage.disabled-providers:MISTRAL}")
                    String streamUsageDisabledProviders,
            // BYOK Gemini/Vertex agents carry no admin thinking-effort setting, and
            // Gemini 2.5/3 think by default out of the SAME maxOutputTokens budget
            // the reply needs - unbounded, that produced truncated or empty replies.
            // "low" = 1024-token budget on 2.5, LOW level on 3 (see
            // HostedLlmChatClientFactory#applyVertexThinkingConfig). Blank = leave
            // the provider default.
            @Value("${app.llm.gemini.byok-reasoning-effort:low}") String geminiByokReasoningEffort,
            @Value("${app.llm.genai.client-cache.max-size:256}") int genAiClientCacheMaxSize,
            @Value("${app.llm.genai.client-cache.idle-ttl:30m}") Duration genAiClientCacheIdleTtl,
            // Opt-in for provider-enforced JSON schemas (OpenAI json_schema / Gemini
            // responseSchema). false (the default) = a requested schema is ignored and the
            // request is exactly what it was before native schemas existed.
            @Value("${app.llm.structured-output.native-enabled:false}")
                    boolean nativeStructuredOutputs) {
        this.nativeStructuredOutputs = nativeStructuredOutputs;
        this.retry =
                new SpringRetryLlmRetry(retryMaxAttempts, retryInitialBackoff, retryMaxBackoff);
        this.observationRegistry =
                observationsEnabled
                        ? observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP)
                        : ObservationRegistry.NOOP;
        this.defaultAgentAdvisors = defaultAgentAdvisors;
        this.streamUsageDisabled = parseProviders(streamUsageDisabledProviders);
        this.geminiByokReasoningEffort = geminiByokReasoningEffort;
        this.genAiClients = new GenAiClientCache(genAiClientCacheMaxSize, genAiClientCacheIdleTtl);
    }

    // ------------------------------------------------------- structured output

    /**
     * Applies a per-call {@link com.agenticai.core.chat.structured.StructuredSchemaSpec} (from
     * {@code providerOptions}) to the OpenAI/Gemini default options: a native JSON schema when the
     * flag is on and the model supports it. In every other case - no schema requested, flag off, or
     * a model outside the allowlist - the options are returned unchanged, because these call sites
     * sent no response format before and some models reject plain JSON mode with a 400. Never
     * mutates {@code options}.
     */
    public <O extends org.springframework.ai.chat.prompt.ChatOptions> O applyStructuredSchema(
            O options, Map<String, Object> providerOptions) {
        var spec = com.agenticai.core.chat.structured.StructuredSchemaSpec.from(providerOptions);
        if (spec == null
                || !nativeStructuredOutputs
                || !com.agenticai.core.chat.structured.StructuredOutput.supportsJsonSchema(
                        options)) {
            return options;
        }
        return com.agenticai.core.chat.structured.StructuredOutput.withJsonSchema(
                options, spec.name(), spec.jsonSchema(), nativeStructuredOutputs);
    }

    /**
     * Anthropic counterpart of {@link #applyStructuredSchema}: wraps {@code chatModel} so a
     * per-call {@link com.agenticai.core.chat.structured.StructuredSchemaSpec} is enforced by
     * FORCED TOOL USE (schema as the pseudo tool's {@code input_schema}, {@code tool_choice} pinned
     * to it, the tool input returned as the reply text). Returns {@code chatModel} itself - today's
     * behaviour - when: the flag is off; no schema was requested; the model id does not support
     * forced tool choice; the schema is not representable as a tool input schema; or extended
     * thinking is on for this client ({@code thinkingOn}; Anthropic rejects a forced tool_choice
     * together with thinking, so the request is never built).
     */
    public ChatModel applyAnthropicStructuredSchema(
            ChatModel chatModel,
            String model,
            boolean thinkingOn,
            Map<String, Object> providerOptions) {
        var spec = com.agenticai.core.chat.structured.StructuredSchemaSpec.from(providerOptions);
        if (spec == null || !anthropicToolForcing) {
            return chatModel;
        }
        if (thinkingOn) {
            log.info(
                    "Anthropic forced-tool structured output skipped: extended thinking is on for"
                            + " model {}",
                    model);
            return chatModel;
        }
        if (model == null || model.isBlank()) {
            // Spring AI 2 substitutes a default Claude model for a null one, which would make the
            // probe below pass for a request whose real model is unknown.
            return chatModel;
        }
        var probe =
                org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                        .model(model)
                        .build();
        if (!com.agenticai.core.chat.structured.StructuredOutput.supportsAnthropicToolForcing(
                probe)) {
            return chatModel;
        }
        var schema =
                com.agenticai.core.chat.structured.StructuredOutput.anthropicToolInputSchema(
                        spec.jsonSchema());
        if (schema == null) {
            log.info(
                    "Anthropic forced-tool structured output skipped: schema '{}' is not"
                            + " representable as a tool input schema; using prompt-and-validate",
                    spec.name());
            return chatModel;
        }
        return new AnthropicStructuredToolChatModel(
                chatModel,
                com.agenticai.core.chat.structured.StructuredOutput.anthropicToolName(spec.name()),
                schema);
    }

    /**
     * Ollama counterpart of {@link #applyStructuredSchema}: {@code response_format: json_schema}
     * when {@code app.llm.structured-output.ollama-format.enabled} is on and a schema was
     * requested; otherwise {@code options} unchanged. Never mutates {@code options}.
     */
    public <O extends org.springframework.ai.chat.prompt.ChatOptions> O applyOllamaStructuredSchema(
            O options, Map<String, Object> providerOptions) {
        var spec = com.agenticai.core.chat.structured.StructuredSchemaSpec.from(providerOptions);
        if (spec == null || !ollamaFormat) {
            return options;
        }
        return com.agenticai.core.chat.structured.StructuredOutput.withOllamaFormat(
                options, spec.name(), spec.jsonSchema());
    }

    /**
     * OpenAI strict function calling ({@code app.llm.tools.strict.enabled}): with the flag on, adds
     * the body rewriter of {@link com.agenticai.core.chat.structured.ToolStrictMode} to {@code api}
     * (it runs on the blocking and the streamed request alike: both go through the SDK's one HTTP
     * client); with it off returns {@code api} untouched.
     */
    OpenAiCompatModelBuilder.Api withToolStrict(OpenAiCompatModelBuilder.Api api) {
        return strictTools
                ? api.bodyTransform(com.agenticai.core.chat.structured.ToolStrictMode::rewrite)
                : api;
    }

    // ------------------------------------------------------------------ retry

    /** The bounded retry policy (see {@link LlmRetry}) every {@code ChatModel} shares. */
    public LlmRetry retry() {
        return retry;
    }

    /** The bounded retry policy as the spring-retry template Spring AI 1.x model builders take. */
    public RetryTemplate retryTemplate() {
        return retry.forSpringAi();
    }

    /**
     * Same policy with the default settings, for code that builds a model without a Spring context
     * (tests, {@link GeminiMixedContentToolCallChatModel}'s convenience constructor).
     */
    public static LlmRetry defaultRetry() {
        return DefaultRetry.RETRY;
    }

    private static final class DefaultRetry {
        static final LlmRetry RETRY =
                new SpringRetryLlmRetry(2, Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    static RetryTemplate boundedRetryTemplate(
            int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        return new SpringRetryLlmRetry(maxAttempts, initialBackoff, maxBackoff).forSpringAi();
    }

    /**
     * Which failures are worth a second attempt; the policy lives in {@link SpringRetryLlmRetry}.
     */
    static boolean isTransient(Throwable failure) {
        return SpringRetryLlmRetry.isTransientFailure(failure);
    }

    // ------------------------------------------------------ observation + client

    public ObservationRegistry observationRegistry() {
        return observationRegistry;
    }

    /**
     * Where the tool loop sits in every client's advisor chain (ADR-0012 P3). In Spring AI 2 the
     * loop is no longer inside the {@code ChatModel}: {@code ToolCallingAdvisor} runs it, and it
     * accumulates the token usage of all iterations into ONE response. Advisors with a LOWER order
     * value run outside it and therefore see one call with the turn's total usage - exactly what
     * they saw when the loop was inside the model - while advisors with a higher value would see
     * (and, for metering, double count) every iteration.
     *
     * <p>Every advisor in this codebase is deliberately placed OUTSIDE: {@code
     * LlmGatewayContentPolicyAdvisor} (-1500), {@code SystemMessageMergingAdvisor} (-1000), {@code
     * LlmMetricsAdvisor} / {@code FreeTierTokenTrackingAdvisor} / {@code
     * PerUserBudgetTrackingAdvisor} (0) and the {@code DefaultAgentAdvisor} beans such as core/rl's
     * trajectory advisor (100). Metering therefore records one row per turn with the accumulated
     * totals - never per iteration, never both. Spring AI's default ({@code HIGHEST_PRECEDENCE +
     * 300}) would have put the loop OUTSIDE all of them; do not change this value without
     * re-reading {@code MeteringCharacterisationTest}.
     */
    public static final int TOOL_CALLING_ADVISOR_ORDER = 1000;

    // Text-form tool-call recovery for every provider (see TextToolCallRecoveringChatModel).
    // Setter-injected so the hand-built test instances keep their constructor; null = unwrapped.
    private ToolCallRecovery toolCallRecovery;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setToolCallRecovery(ToolCallRecovery toolCallRecovery) {
        this.toolCallRecovery = toolCallRecovery;
    }

    // Constrained tool router for hosted-pool rows that opted in (see
    // ConstrainedToolRouterChatModel).
    private ConstrainedToolRouting constrainedToolRouting;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setConstrainedToolRouting(ConstrainedToolRouting constrainedToolRouting) {
        this.constrainedToolRouting = constrainedToolRouting;
    }

    /**
     * {@code ChatClient.builder(model)} with the app's registry instead of NOOP and the platform's
     * tool loop: the caller's {@code toolCallingManager} (from {@link
     * ResilientToolCallingManagerFactory}) is seated on the {@code ToolCallingAdvisor} Spring AI 2
     * auto-registers, at {@link #TOOL_CALLING_ADVISOR_ORDER}. The same manager is given to the
     * model builders (they still use it to resolve tool definitions for the request). The model is
     * wrapped in {@link TextToolCallRecoveringChatModel} when {@link ToolCallRecovery} is wired and
     * enabled - this is the single seam every factory in this package builds its client through, so
     * a provider added later is covered without wiring of its own.
     */
    public ChatClient.Builder chatClientBuilder(
            ChatModel chatModel, ToolCallingManager toolCallingManager) {
        return chatClientBuilder(chatModel, toolCallingManager, null);
    }

    /**
     * {@link #chatClientBuilder(ChatModel, ToolCallingManager)} for a model that implements no tool
     * calling of its own ({@link AwsBedrockChatModel}): the advisor is seated with Spring AI's
     * stock manager, since no factory-built resilient manager applies to that provider.
     */
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return chatClientBuilder(chatModel, ToolCallingManager.builder().build(), null);
    }

    /**
     * {@link #chatClientBuilder(ChatModel, ToolCallingManager)} plus, when {@code routerMode} is
     * non-null and {@link ConstrainedToolRouting} is wired and enabled, the {@link
     * ConstrainedToolRouterChatModel} outermost, so it owns the tool loop for that row. A null mode
     * is exactly the two-argument form: no request of a row that did not opt in changes.
     */
    public ChatClient.Builder chatClientBuilder(
            ChatModel chatModel,
            ToolCallingManager toolCallingManager,
            com.agenticai.core.providers.ToolRouterMode routerMode) {
        ChatModel model = toolCallRecovery == null ? chatModel : toolCallRecovery.wrap(chatModel);
        if (routerMode != null && constrainedToolRouting != null) {
            model = constrainedToolRouting.wrap(model, routerMode);
        }
        return ChatClient.builder(
                model,
                observationRegistry,
                null,
                null,
                CancellationAwareToolCallingAdvisor.builder()
                        .toolCallingManager(toolCallingManager)
                        .advisorOrder(TOOL_CALLING_ADVISOR_ORDER));
    }

    /**
     * The per-call advisors a factory built (metrics, system-message merging, ...) followed by
     * every {@link DefaultAgentAdvisor} bean in {@code @Order} order - the list Anthropic alone
     * used to assemble.
     */
    public List<Advisor> advisors(Advisor... perCall) {
        List<Advisor> advisors = new ArrayList<>(Arrays.asList(perCall));
        // Turn on the OTel GenAI invoke_agent span with the app's real registry.
        for (Advisor advisor : perCall) {
            if (advisor instanceof LlmMetricsAdvisor metricsAdvisor) {
                metricsAdvisor
                        .withObservationRegistry(observationRegistry)
                        .withGenAiAttributeNames(GenAiAttributeNames.of(genAiAttrs))
                        .withLangfuseUserId(
                                LangfuseUserId.of(langfuseUserIdMode, langfuseUserIdSalt));
            }
        }
        defaultAgentAdvisors.orderedStream().forEach(advisors::add);
        return advisors;
    }

    // --------------------------------------------------------- stream usage

    /**
     * Whether to ask this OpenAI-compatible provider for {@code stream_options.include_usage}.
     * Without it a streamed turn's final chunk carries no usage and every budget/cost reader sees 0
     * tokens.
     */
    public boolean streamUsage(ProviderName provider) {
        return !streamUsageDisabled.contains(provider);
    }

    static Set<ProviderName> parseProviders(String csv) {
        Set<ProviderName> providers = EnumSet.noneOf(ProviderName.class);
        if (csv == null) {
            return providers;
        }
        for (String raw : csv.split(",")) {
            String name = raw.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            try {
                providers.add(ProviderName.valueOf(name));
            } catch (IllegalArgumentException e) {
                log.warn(
                        "Ignoring unknown provider '{}' in app.llm.stream-usage.disabled-providers",
                        raw);
            }
        }
        return providers;
    }

    // ---------------------------------------------- OpenAI reasoning families

    /**
     * Whether an OpenAI model id names a reasoning family (o1/o3/o4..., gpt-5*) - those reject
     * {@code max_tokens} (they require {@code max_completion_tokens}) and reject any non-default
     * {@code temperature}/ {@code top_p}, so sending the usual trio 400s every turn. An {@code
     * "openai/"} prefix (OpenRouter's namespacing) is ignored; other vendors' namespaced ids never
     * match. {@code gpt-5-chat*} is the non-reasoning chat variant and keeps normal sampling.
     */
    static boolean isOpenAiReasoningModel(String model) {
        if (model == null) {
            return false;
        }
        String id = model.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("openai/")) {
            id = id.substring("openai/".length());
        }
        if (id.startsWith("gpt-5")) {
            return !id.startsWith("gpt-5-chat");
        }
        return id.matches("o[1-9](?:[-.].*)?");
    }

    /**
     * Sets the token limit and sampling on an OpenAI-compatible options builder: {@code
     * max_tokens}+{@code temperature}+{@code top_p} for ordinary models, {@code
     * max_completion_tokens} and nothing else for reasoning families (see {@link
     * #isOpenAiReasoningModel}). Then applies {@link SamplingOptions}; reasoning models reject
     * {@code stop} and the penalties too, so only {@code seed} survives for them.
     */
    static void applyOpenAiTokensAndSampling(
            OpenAiChatOptions.Builder builder,
            ProviderName provider,
            String model,
            int maxTokens,
            double temperature,
            double topP,
            SamplingOptions sampling) {
        if (isOpenAiReasoningModel(model)) {
            builder.maxCompletionTokens(maxTokens);
            if (sampling.seed() != null) {
                builder.seed(sampling.seed());
            }
            logDroppedSampling(
                    provider,
                    model,
                    sampling,
                    "topK",
                    "stop",
                    "frequencyPenalty",
                    "presencePenalty");
            return;
        }
        builder.maxTokens(maxTokens).temperature(temperature).topP(topP);
        sampling.applyTo(builder);
        logDroppedSampling(provider, model, sampling, "topK");
    }

    /**
     * DEBUG-logs the {@link SamplingOptions} fields in {@code unsupported} that the agent actually
     * set - the "silently omitted" contract of SamplingOptions stays, but an operator can now see
     * it happen.
     */
    static void logDroppedSampling(
            ProviderName provider, String model, SamplingOptions sampling, String... unsupported) {
        if (!log.isDebugEnabled() || sampling == null || sampling.isEmpty()) {
            return;
        }
        List<String> dropped = setFields(sampling, unsupported);
        if (!dropped.isEmpty()) {
            log.debug(
                    "[{}] model {} does not accept sampling option(s) {}; omitted from the request",
                    provider,
                    model,
                    dropped);
        }
    }

    static List<String> setFields(SamplingOptions sampling, String... fields) {
        List<String> set = new ArrayList<>();
        for (String field : fields) {
            boolean present =
                    switch (field) {
                        case "topK" -> sampling.topK() != null;
                        case "stop" -> !sampling.stopSequences().isEmpty();
                        case "frequencyPenalty" -> sampling.frequencyPenalty() != null;
                        case "presencePenalty" -> sampling.presencePenalty() != null;
                        case "seed" -> sampling.seed() != null;
                        default -> false;
                    };
            if (present) {
                set.add(field);
            }
        }
        return set;
    }

    // ------------------------------------------------------- Gemini thinking

    /**
     * Bounds Gemini 2.5/3 thinking for BYOK Gemini and Vertex agents using the same mapping the
     * hosted pool uses ({@link HostedLlmChatClientFactory#applyVertexThinkingConfig}), driven by
     * {@code app.llm.gemini.byok-reasoning-effort}. No-op for other models or a blank/unknown
     * setting, and for 2.5 Flash-Lite, which does not think unless asked - a budget there would
     * turn thinking ON and add cost.
     */
    public void applyGeminiByokThinking(GoogleGenAiChatOptions.Builder builder, String model) {
        if (model != null && model.startsWith("gemini-2.5") && model.contains("flash-lite")) {
            return;
        }
        HostedLlmChatClientFactory.applyVertexThinkingConfig(
                builder, model, geminiByokReasoningEffort);
    }

    // ------------------------------------------------------ genai client cache

    /**
     * A google-genai {@link Client} for this credential/endpoint, reused across turns. Each Client
     * owns an OkHttp pool and dispatcher; building one per turn and never closing it (the previous
     * behaviour in all three genai call sites) leaked both. {@code keyParts} must identify
     * EVERYTHING the client was built from - credential material, endpoint, project, location - and
     * is only ever held as a SHA-256 digest.
     */
    public Client genAiClient(Supplier<Client> factory, String... keyParts) {
        return genAiClients.get(fingerprint(keyParts), factory);
    }

    static String fingerprint(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                sha.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public void destroy() {
        genAiClients.closeAll();
    }

    /**
     * Size- and idle-bounded LRU of genai clients (Caffeine is not on core/chat's classpath).
     * Evicted clients are closed. Closing is safe for a client still mid-request: {@code
     * Client#close} only shuts OkHttp's async dispatcher executor and evicts IDLE pooled
     * connections, and the synchronous calls Spring AI makes use neither.
     */
    static final class GenAiClientCache {

        private final int maxSize;
        private final long idleTtlNanos;
        private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

        /**
         * Guards {@link #entries}. A ReentrantLock, not {@code synchronized}: a miss builds the
         * client inside the critical section (credential/transport setup), and {@code synchronized}
         * around blocking work pins a virtual thread to its carrier on JDK 21 (JAVA-024).
         */
        private final java.util.concurrent.locks.ReentrantLock lock =
                new java.util.concurrent.locks.ReentrantLock();

        private record Entry(Client client, long lastUsedNanos) {}

        GenAiClientCache(int maxSize, Duration idleTtl) {
            this.maxSize = Math.max(1, maxSize);
            this.idleTtlNanos = idleTtl.toNanos();
        }

        Client get(String key, Supplier<Client> factory) {
            List<Client> evicted = new ArrayList<>();
            Client client;
            lock.lock();
            try {
                long now = System.nanoTime();
                Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Entry> e = it.next();
                    if (!e.getKey().equals(key)
                            && now - e.getValue().lastUsedNanos() > idleTtlNanos) {
                        evicted.add(e.getValue().client());
                        it.remove();
                    }
                }
                Entry existing = entries.get(key);
                client = existing != null ? existing.client() : factory.get();
                entries.put(key, new Entry(client, now));
                while (entries.size() > maxSize) {
                    Iterator<Map.Entry<String, Entry>> eldest = entries.entrySet().iterator();
                    evicted.add(eldest.next().getValue().client());
                    eldest.remove();
                }
            } finally {
                lock.unlock();
            }
            evicted.forEach(GenAiClientCache::closeQuietly);
            return client;
        }

        int size() {
            lock.lock();
            try {
                return entries.size();
            } finally {
                lock.unlock();
            }
        }

        void closeAll() {
            List<Client> all;
            lock.lock();
            try {
                all = entries.values().stream().map(Entry::client).toList();
                entries.clear();
            } finally {
                lock.unlock();
            }
            all.forEach(GenAiClientCache::closeQuietly);
        }

        private static void closeQuietly(Client client) {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.debug("Closing evicted google-genai client failed", e);
            }
        }
    }
}
