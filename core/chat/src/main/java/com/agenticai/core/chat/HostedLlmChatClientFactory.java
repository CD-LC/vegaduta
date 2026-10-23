package com.agenticai.core.chat;

import com.agenticai.core.providers.PlatformHostedLlmAuthType;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.VertexAccessTokenProvider;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link ChatClient} from a {@link PlatformHostedLlmProvider} row - the FREE-tier
 * counterpart to {@code GrokAgentChatClientFactory}/{@code OpenRouterAgentChatClientFactory},
 * except {@code baseUrl}/{@code model}/ the API key are runtime data from the row instead of a
 * compile-time constant + a per-tenant decrypted BYOK credential. Not registered in {@code
 * ChatClientRegistry} (that map is keyed by {@link ProviderName}, one fixed factory per BYOK
 * provider) - this is called directly by whatever resolves the FREE-tier routing decision (see
 * {@code AgentChatService}), since which row to use is a runtime pick from a pool, not a static
 * provider-to-factory lookup.
 *
 * <p><b>{@code baseUrl} must NOT include a trailing {@code "/v1"}</b> - same convention every other
 * factory in this codebase already follows ({@code OpenAIAgentChatClientFactory}'s {@code
 * "https://api.openai.com"}, {@code GrokAgentChatClientFactory}'s {@code "https://api.x.ai"},
 * {@code SarvamAgentChatClientFactory}'s {@code "https://api.sarvam.ai"}, {@code
 * OpenRouterAgentChatClientFactory}'s {@code "https://openrouter.ai/api"} - none of them include
 * {@code /v1}): Spring AI's {@link OpenAiApi} already appends its own default {@code
 * completionsPath} ({@code "/v1/chat/completions"}) onto whatever {@code baseUrl} is configured, so
 * a trailing {@code "/v1"} here would double up into {@code ".../v1/v1/chat/completions"} - a 404
 * already hit and fixed once in production for OpenRouter/Grok. {@code
 * PlatformHostedLlmProviderService#validateKey} hits {@code "{baseUrl}/v1/models"} directly (not
 * through {@link OpenAiApi}), so it does NOT catch this mistake - an admin entering the wrong
 * {@code baseUrl} shape here will validate fine but still 404 on every real chat call (exactly the
 * "already configured but FREE-tier chat doesn't work" bug report this class's {@link
 * #normalizeBaseUrl} was added to fix - a prior version of this javadoc actually gave Together AI's
 * correct example as {@code "https://api.together.xyz/v1"}, which is precisely this mistake).
 * Correct example: Groq is {@code "https://api.groq.com/openai"}. Rather than trust every admin to
 * get this right by hand, {@link #create} now strips a trailing {@code /v1} defensively before
 * building {@link OpenAiApi}, so a misconfigured row degrades to "works anyway" instead of
 * "silently 404s for every FREE-tier user."
 */
@Component
public class HostedLlmChatClientFactory {

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    // Infra audit (2026-07-27) - threaded through to LlmMetricsAdvisor, see
    // AnthropicAgentChatClientFactory's identical field comment.
    private final MeterRegistry meterRegistry;
    private final LlmPricingCatalog pricingCatalog;
    // See ResilientToolCallingManagerFactory. This factory in particular is why
    // that bean exists: the hosted pool is what every FREE-tier agent runs on,
    // which is exactly where the small models that invent tool names and emit
    // malformed tool-call arguments get used.
    private final ResilientToolCallingManagerFactory toolCallingManagers;

    // Outbound HTTP timeouts (2026-07-29 security audit) - OpenAiApi's own
    // default RestClient/WebClient have none at all. See LlmHttpClientFactory.
    private final LlmHttpClientFactory llmHttpClientFactory;

    // V169 - resolves an access token for a VERTEX_ADC/VERTEX_SERVICE_ACCOUNT
    // row, same OAuth2-exchange-and-cache machinery the platform's static
    // Vertex pool (VertexPoolProperties) and tenant BYOK Vertex credentials
    // already share - see #createVertex below.
    private final VertexAccessTokenProvider vertexAccessTokenProvider;

    // Bounded retries (the pool's own failover must not wait behind Spring AI's
    // 10-attempt default), real ObservationRegistry, default advisors, stream
    // usage and the shared genai Client cache - see LlmModelSupport.
    private final LlmModelSupport modelSupport;
    // Shared OpenAI-compatible transport/model construction (ADR-0012 P1b) - see
    // OpenAiCompatModelBuilder.
    private final OpenAiCompatModelBuilder modelBuilder;

    // The install's local model server, to recognise its pool row (see isLocalOllamaRow), and
    // the Ollama context window OllamaAgentChatClientFactory also applies. Field-injected so the
    // one constructor - and every caller that builds this factory by hand - stays as it is.
    @Value("${app.local-models.ollama-base-url:${app.local-llm.bootstrap.base-url:http://ollama:11434}}")
    private String localOllamaBaseUrl = "";

    @Value("${app.llm.ollama.num-ctx:32768}")
    private int ollamaNumCtx = 32768;

    @Value("${app.llm.ollama.reasoning-effort:none}")
    private String ollamaReasoningEffort = "none";

    public HostedLlmChatClientFactory(
            LlmCallMetricsService llmCallMetricsService,
            LlmCallLogService llmCallLogService,
            MeterRegistry meterRegistry,
            LlmPricingCatalog pricingCatalog,
            LlmHttpClientFactory llmHttpClientFactory,
            ResilientToolCallingManagerFactory toolCallingManagers,
            VertexAccessTokenProvider vertexAccessTokenProvider,
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
        this.vertexAccessTokenProvider = vertexAccessTokenProvider;
    }

    /**
     * Pre-thinking-config signature, kept delegating so every existing caller ({@code
     * ModelCapabilitySummaryGenerationService}, {@code LlmCallDiagnosisService}, {@code
     * ErrorExplanationService}) compiles unchanged - those internal one-shot callers carry no admin
     * reasoning-effort setting, and a null effort leaves the request exactly as it was before this
     * knob existed (provider-default thinking).
     */
    public ChatClient create(
            PlatformHostedLlmProvider provider,
            String decryptedApiKey,
            int maxTokens,
            double temperature,
            double topP) {
        return create(provider, decryptedApiKey, maxTokens, temperature, topP, null);
    }

    /**
     * Full entry point (2026-08-12) - adds the admin-selectable Gemini thinking effort. Gemini 2.5+
     * models THINK by default, and thinking tokens are billed as OUTPUT (8.3x the input rate on
     * 2.5-flash) drawn from the same {@code maxTokens} budget the reply needs - before this knob,
     * every hosted-pool turn silently paid for unbounded thinking and routinely got truncated
     * replies. The effort is applied ONLY when the row's model id indicates a thinking Gemini
     * family (see {@link #isThinkingGeminiFamily}) - other OpenAI-compat providers behind this
     * factory (Groq, Together, ...) can 400 on unknown request params, so they must never see it.
     *
     * @param geminiReasoningEffort {@code none|low|medium|high} (the platform_setting {@code
     *     platform.hosted-llm.gemini-reasoning-effort} read by {@code FreeTierChatClientService}),
     *     or null to leave the request untouched. Unrecognized values are ignored, never forwarded.
     */
    public ChatClient create(
            PlatformHostedLlmProvider provider,
            String decryptedApiKey,
            int maxTokens,
            double temperature,
            double topP,
            String geminiReasoningEffort) {
        return create(
                provider,
                decryptedApiKey,
                maxTokens,
                temperature,
                topP,
                geminiReasoningEffort,
                SamplingOptions.NONE);
    }

    /**
     * Full entry point including the agent's {@link SamplingOptions}. OpenAI-compatible rows
     * receive stop / frequency_penalty / presence_penalty / seed (topK is omitted - not part of
     * that API); Vertex rows receive topK, stop and the penalties (seed is not exposed). Null
     * sampling means none.
     */
    public ChatClient create(
            PlatformHostedLlmProvider provider,
            String decryptedApiKey,
            int maxTokens,
            double temperature,
            double topP,
            String geminiReasoningEffort,
            SamplingOptions sampling) {
        SamplingOptions samplingOptions = sampling == null ? SamplingOptions.NONE : sampling;
        if (PlatformHostedLlmAuthType.isVertex(provider.getAuthType())) {
            return createVertex(
                    provider,
                    decryptedApiKey,
                    maxTokens,
                    temperature,
                    topP,
                    geminiReasoningEffort,
                    samplingOptions);
        }
        ResolvedEndpoint endpoint = resolveEndpoint(provider);
        String model = resolveModel(provider);
        OpenAiCompatModelBuilder.Api apiBuilder = modelBuilder.api(endpoint.baseUrl());
        // The install's own Ollama (LocalLlmPoolBootstrap / LocalModelsService rows) gets what a
        // tenant OLLAMA provider row already gets in OllamaAgentChatClientFactory: the
        // reasoning-field alias, so a thinking model's reasoning reaches the `thinking` step
        // events instead of being dropped, and the num_ctx override below. Other pool rows are
        // untouched: a cloud provider can 400 on an unknown body field.
        boolean localOllama = isLocalOllamaRow(provider);
        if (localOllama) {
            apiBuilder =
                    apiBuilder
                            .webClientBuilder(
                                    modelBuilder
                                            .webClientBuilder()
                                            .filter(OllamaReasoningAlias.streamFilter()))
                            .restClientBuilder(
                                    modelBuilder
                                            .restClientBuilder()
                                            .requestInterceptor(
                                                    OllamaReasoningAlias.restInterceptor()));
        }
        // Keyless rows (a local Ollama / llama.cpp / vLLM entry made by the local-LLM bootstrap)
        // send no Authorization header at all, like OllamaAgentChatClientFactory. Trimmed for the
        // same reason the model is (see resolveModel): the key is decrypted straight out of the
        // row, and rows written before the upsert DTO started trimming can carry a pasted
        // trailing newline, which would go out in the Authorization header verbatim.
        apiBuilder =
                isKeyless(decryptedApiKey)
                        ? apiBuilder.noApiKey()
                        : apiBuilder.apiKey(decryptedApiKey.trim());
        OpenAiApi openAiApi = apiBuilder.completionsPath(endpoint.completionsPath()).build();

        OpenAiChatOptions.Builder optionsBuilder =
                openAiCompatOptionsBuilder(
                        model,
                        modelSupport.streamUsage(ProviderName.PLATFORM_HOSTED),
                        maxTokens,
                        temperature,
                        topP,
                        samplingOptions);
        // Gemini's OpenAI-compat layer accepts reasoning_effort (Spring AI
        // 1.1.7's OpenAiChatOptions.Builder#reasoningEffort, verified in the
        // installed jar - serialized as ChatCompletionRequest.reasoningEffort)
        // and maps it to a thinking budget server-side. Null for every
        // non-thinking-Gemini row, so Groq/Together/other OpenAI-compat
        // providers never receive a param they might reject.
        String reasoningEffort = openAiCompatReasoningEffort(model, geminiReasoningEffort);
        if (reasoningEffort != null) {
            optionsBuilder.reasoningEffort(reasoningEffort);
        } else if (localOllama && effectiveReasoningEffort(provider) != null) {
            // Thinking models (qwen3.5, qwen3, deepseek-r1, gpt-oss) reason by default, and on a
            // laptop that is the whole wait: measured 2026-10-18 on qwen3.5:4b, a one-line question
            // spent 40+ s and 600 tokens thinking before it answered, against 5 s with reasoning
            // off. Ollama's OpenAI-compatible endpoint maps reasoning_effort to its think switch
            // ("none" = off; it accepts the field for non-thinking models too). A person who wants
            // the reasoning sets app.llm.ollama.reasoning-effort to low, medium or high.
            optionsBuilder.reasoningEffort(effectiveReasoningEffort(provider));
        }
        OpenAiChatOptions options = optionsBuilder.build();
        if (localOllama && ollamaNumCtx > 0) {
            options.setExtraBody(Map.of("options", Map.of("num_ctx", ollamaNumCtx)));
        }

        OpenAiChatModel chatModel = modelBuilder.chatModel(openAiApi, options);

        return modelSupport
                // V500 - a row graded TEXT/NONE on a server that can enforce a schema or
                // tool_choice=required gets the constrained tool router; null for every other row.
                .chatClientBuilder(
                        chatModel,
                        provider.effectiveToolRouterMode(),
                        // The text-call recovery is a small-model workaround: by what is known of this row.
                        modelSupport.capabilityOf(provider.getToolCallGrade(), provider.effectiveSmallModel()))
                .defaultAdvisors(
                        modelSupport.advisors(
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.PLATFORM_HOSTED,
                                        model,
                                        meterRegistry,
                                        pricingCatalogFor(provider))))
                .build();
    }

    /**
     * {@code VERTEX_ADC}/{@code VERTEX_SERVICE_ACCOUNT} branch (V169) - builds a {@link ChatClient}
     * from Spring AI's native {@link GoogleGenAiChatModel} in Vertex mode instead of {@link
     * OpenAiApi}, following the exact same pattern {@link VertexAiChatClientFactory} already
     * establishes for tenant BYOK Vertex credentials: exchange a credential for a {@link
     * GoogleCredentials}, point {@link Client.Builder#vertexAI(boolean)} at it with {@code
     * project}/ {@code location}, and build {@link GoogleGenAiChatModel} on top. The two
     * differences from that class are (a) the credential source - here it's either ADC or this
     * row's own decrypted service-account JSON, resolved via {@link
     * VertexAccessTokenProvider#accessTokenForHostedProvider}, not a {@code
     * VertexCredential}-encoded BYOK blob - and (b) metrics are tagged {@link
     * ProviderName#PLATFORM_HOSTED} (matching every other hosted-pool row), not {@link
     * ProviderName#GOOGLE_VERTEX}.
     */
    /**
     * True for the pool row that serves this install's own Ollama: a row whose {@code
     * providerName} says OLLAMA, or whose base URL is the operator-configured local model server
     * ({@code app.local-models.ollama-base-url}, the same URL LocalModelOllamaGateway talks to).
     */
    public boolean isLocalOllamaRow(PlatformHostedLlmProvider provider) {
        if (provider == null) {
            return false;
        }
        if (ProviderName.OLLAMA.name().equalsIgnoreCase(provider.getProviderName())) {
            return true;
        }
        String local = normalizeBaseUrl(localOllamaBaseUrl);
        String row = normalizeBaseUrl(provider.getBaseUrl());
        return local != null && !local.isBlank() && row != null && local.equalsIgnoreCase(row);
    }

    /**
     * The thinking switch for a local Ollama row: the row's own setting (Providers -> Local model),
     * else {@code app.llm.ollama.reasoning-effort}. Null or blank means "leave the model's default".
     */
    String effectiveReasoningEffort(PlatformHostedLlmProvider provider) {
        String value = provider.getReasoningEffort() != null ? provider.getReasoningEffort() : ollamaReasoningEffort;
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** True when a pool row carries no usable API key, so no Authorization header is sent. */
    static boolean isKeyless(String decryptedApiKey) {
        return decryptedApiKey == null || decryptedApiKey.isBlank();
    }

    /**
     * Base OpenAI-compatible options for a pool row, including the agent's sampling
     * (stop/penalties/seed; topK omitted). A pool row may point at OpenAI (or OpenRouter) with a
     * gpt-5*, o-series id; those need max_completion_tokens and no temperature/top_p, and keep only
     * seed. Package-private so the threading is unit-testable.
     */
    static OpenAiChatOptions.Builder openAiCompatOptionsBuilder(
            String model,
            boolean streamUsage,
            int maxTokens,
            double temperature,
            double topP,
            SamplingOptions sampling) {
        OpenAiChatOptions.Builder builder =
                OpenAiCompatModelBuilder.baseOptions(model, streamUsage);
        LlmModelSupport.applyOpenAiTokensAndSampling(
                builder,
                ProviderName.PLATFORM_HOSTED,
                model,
                maxTokens,
                temperature,
                topP,
                sampling == null ? SamplingOptions.NONE : sampling);
        return builder;
    }

    private ChatClient createVertex(
            PlatformHostedLlmProvider provider,
            String decryptedSecretOrNull,
            int maxTokens,
            double temperature,
            double topP,
            String geminiReasoningEffort,
            SamplingOptions sampling) {
        String model = resolveModel(provider);
        boolean useAdc = PlatformHostedLlmAuthType.VERTEX_ADC.equals(provider.getAuthType());
        String location =
                provider.getVertexLocation() == null || provider.getVertexLocation().isBlank()
                        ? PlatformHostedLlmAuthType.DEFAULT_VERTEX_LOCATION
                        : provider.getVertexLocation();

        String accessTokenValue =
                vertexAccessTokenProvider.accessTokenForHostedProvider(
                        provider.getVertexProjectId(),
                        useAdc,
                        useAdc ? null : decryptedSecretOrNull);
        // Same "wrap the token we already fetched/cached rather than hand the
        // SDK the credential material a second time" move VertexAiChatClientFactory
        // makes for tenant BYOK Vertex credentials.
        // Reused per (token, project, location) instead of built - and leaked -
        // per turn; see LlmModelSupport#genAiClient.
        Client genAiClient =
                modelSupport.genAiClient(
                        () ->
                                Client.builder()
                                        .vertexAI(true)
                                        .project(provider.getVertexProjectId())
                                        .location(location)
                                        .credentials(
                                                GoogleCredentials.create(
                                                        new AccessToken(accessTokenValue, null)))
                                        .httpOptions(
                                                HttpOptions.builder()
                                                        .timeout(
                                                                Math.toIntExact(
                                                                        llmHttpClientFactory
                                                                                .readTimeout()
                                                                                .toMillis()))
                                                        .build())
                                        .build(),
                        "vertex-hosted",
                        accessTokenValue,
                        provider.getVertexProjectId(),
                        location);

        GoogleGenAiChatOptions.Builder optionsBuilder =
                GoogleGenAiChatOptions.builder()
                        .model(model)
                        .maxOutputTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP);
        sampling.applyTo(optionsBuilder);
        // Same admin knob as the OpenAI-compat branch above, expressed through
        // the genai SDK's native ThinkingConfig (Spring AI 1.1.7's
        // GoogleGenAiChatOptions.Builder#thinkingBudget/#thinkingLevel,
        // verified in the installed jar). No-op when the effort is null/unknown
        // or the model isn't a thinking Gemini family.
        applyVertexThinkingConfig(optionsBuilder, model, geminiReasoningEffort);
        GoogleGenAiChatOptions options = optionsBuilder.build();

        GoogleGenAiChatModel chatModel =
                GoogleGenAiChatModel.builder()
                        .genAiClient(genAiClient)
                        .defaultOptions(options)
                        .toolCallingManager(toolCallingManagers.create())
                        .retryTemplate(modelSupport.retryTemplate())
                        .observationRegistry(modelSupport.observationRegistry())
                        .build();

        return modelSupport
                .chatClientBuilder(
                        chatModel,
                        null,
                        modelSupport.capabilityOf(provider.getToolCallGrade(), provider.effectiveSmallModel()))
                .defaultAdvisors(
                        modelSupport.advisors(
                                // Gemini rejects >1 system message; the platform routinely sends
                                // several. Must run before the model call - see the advisor's
                                // javadoc.
                                new SystemMessageMergingAdvisor(),
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.PLATFORM_HOSTED,
                                        model,
                                        meterRegistry,
                                        pricingCatalogFor(provider))))
                .build();
    }

    /**
     * The stored model id, trimmed. Confirmed in production: a row saved with {@code "
     * gemini-2.0-flash-lite"} (one leading space, invisible in the admin form) made every
     * platform-hosted turn fail with Google's {@code 404 NOT_FOUND} - the id goes out verbatim in
     * the request body, and nothing on the write path normalized it. {@link
     * com.agenticai.core.providers.PlatformHostedLlmProviderUpsertRequest} now trims on save, but
     * this is the read side of the same fix: rows persisted BEFORE that change are still in the
     * table, and this is the single place every hosted-pool turn reads the model from, so trimming
     * here makes an already-broken row work again with no data migration. Same defensive posture as
     * {@link #normalizeBaseUrl} - a misconfigured row degrades to "works anyway" rather than
     * "silently 404s for every user."
     */
    /**
     * V484 - a {@link PlatformHostedLlmProvider#isZeroCost() zero-cost} row (a model the operator
     * runs themselves) is priced at $0 everywhere a call is recorded; every other row uses the real
     * catalog, where an unknown model still gets the conservative fallback rate.
     */
    private LlmPricingCatalog pricingCatalogFor(PlatformHostedLlmProvider provider) {
        return provider != null && provider.isZeroCost() ? LlmPricingCatalog.zeroCost() : pricingCatalog;
    }

    private static String resolveModel(PlatformHostedLlmProvider provider) {
        String model = provider.getModel();
        return model == null ? null : model.trim();
    }

    /**
     * The {@code (baseUrl, completionsPath)} pair to feed {@link OpenAiApi} for a given row,
     * extracted so the URL-shaping decision is unit-testable without reaching into {@link
     * OpenAiApi}'s package-private internals.
     *
     * <ul>
     *   <li><b>Custom path</b> ({@link PlatformHostedLlmProvider#getCompletionsPath()} non-blank,
     *       e.g. Gemini): {@code baseUrl} is used verbatim and the custom path is set - {@link
     *       #normalizeBaseUrl} is deliberately NOT applied, since a non-standard completions path
     *       is exactly the case where blind {@code /v1}-stripping would be wrong.
     *   <li><b>Conventional</b> ({@code completionsPath} null/blank): behaves exactly as before -
     *       {@link #normalizeBaseUrl} strips a trailing {@code /v1} and {@link OpenAiApi}'s default
     *       {@code /v1/chat/completions} path is left in place.
     * </ul>
     */
    static ResolvedEndpoint resolveEndpoint(PlatformHostedLlmProvider provider) {
        String completionsPath = provider.getCompletionsPath();
        if (completionsPath != null && !completionsPath.isBlank()) {
            // strip(), not stripTrailing(): a LEADING space survives
            // stripTrailing() and makes the resulting URI unparseable.
            String baseUrl = provider.getBaseUrl() == null ? null : provider.getBaseUrl().strip();
            return new ResolvedEndpoint(baseUrl, completionsPath.trim());
        }
        return new ResolvedEndpoint(normalizeBaseUrl(provider.getBaseUrl()), null);
    }

    /**
     * Resolved OpenAI-compat target; {@code completionsPath} null means "use the OpenAiApi
     * default".
     */
    record ResolvedEndpoint(String baseUrl, String completionsPath) {}

    /**
     * Strips exactly one trailing {@code /v1} (with or without a trailing slash) from an
     * admin-entered {@code baseUrl} - see this class's own javadoc for why: {@link OpenAiApi}'s
     * default {@code completionsPath} already contributes the {@code /v1}, so leaving one in {@code
     * baseUrl} too produces {@code .../v1/v1/chat/completions} and every real chat call 404s, even
     * though {@code PlatformHostedLlmProviderService #validateKey} (which hits {@code /v1/models}
     * directly, not through {@link OpenAiApi}) saved the row successfully. Only strips a bare
     * trailing {@code /v1} segment - a provider whose API genuinely lives under a deeper {@code
     * /v1/...} sub-path unrelated to OpenAI-compat completions is not this bug and is left
     * untouched.
     */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String trimmed = baseUrl.strip();
        if (trimmed.endsWith("/v1")) {
            return trimmed.substring(0, trimmed.length() - "/v1".length());
        }
        if (trimmed.endsWith("/v1/")) {
            return trimmed.substring(0, trimmed.length() - "/v1/".length());
        }
        return trimmed;
    }

    /**
     * Whether this model id names a Gemini family that thinks by default and therefore understands
     * thinking-effort controls (Gemini 2.5 and Gemini 3 lines). Everything else - including the
     * Groq/Together open models and pre-2.5 Gemini - must NEVER receive a thinking param: unknown
     * request fields can 400 on strict OpenAI-compat servers, which would take down the whole
     * hosted row for every FREE-tier user.
     */
    static boolean isThinkingGeminiFamily(String model) {
        return model != null && (model.startsWith("gemini-2.5") || model.startsWith("gemini-3"));
    }

    /**
     * The {@code reasoning_effort} value to send on the OpenAI-compat branch, or null to send
     * nothing. Gemini's OpenAI-compat layer documents {@code none|low|medium|high} mapping to
     * thinking budgets 0/1024/8192/24576. Two clamps, both to avoid a server-side 400 rather than
     * to second-guess the admin: {@code gemini-2.5-pro} and the Gemini 3 line cannot disable
     * thinking at all (budget 0 is rejected - 2.5-pro's documented minimum is 128), so {@code none}
     * degrades to {@code low} (the smallest effort the model accepts) instead of failing every
     * turn.
     */
    static String openAiCompatReasoningEffort(String model, String requestedEffort) {
        String effort = normalizeEffort(requestedEffort);
        if (effort == null || !isThinkingGeminiFamily(model)) {
            return null;
        }
        if ("none".equals(effort)
                && (model.startsWith("gemini-3") || model.startsWith("gemini-2.5-pro"))) {
            return "low";
        }
        return effort;
    }

    /**
     * Vertex-branch counterpart of {@link #openAiCompatReasoningEffort}, expressed through the
     * genai SDK's native ThinkingConfig:
     *
     * <ul>
     *   <li><b>Gemini 2.5</b> uses a numeric {@code thinkingBudget} - the same 0/1024/8192/24576
     *       token budgets Gemini's OpenAI-compat layer maps {@code none|low|medium|high} to, so the
     *       two branches spend identically for the same admin setting. {@code gemini-2.5-pro}
     *       cannot disable thinking, so {@code none} clamps to its documented minimum budget of 128
     *       rather than 400-ing every turn.
     *   <li><b>Gemini 3</b> uses {@code thinkingLevel} (budgets are the 2.5 mechanism). Spring AI
     *       1.1.7 itself rejects anything but LOW/HIGH for Gemini 3 Pro ({@code
     *       validateThinkingLevelForModel} throws at request time), and Gemini 3 cannot disable
     *       thinking, so the four admin values collapse to the two levels every Gemini 3 model
     *       accepts: {@code none|low|medium} &rarr; LOW (cheap-side bias - this whole knob exists
     *       to bound spend), {@code high} &rarr; HIGH.
     * </ul>
     *
     * No-op for a null/unknown effort or a non-thinking-Gemini model.
     */
    static void applyVertexThinkingConfig(
            GoogleGenAiChatOptions.Builder builder, String model, String requestedEffort) {
        String effort = normalizeEffort(requestedEffort);
        if (effort == null || !isThinkingGeminiFamily(model)) {
            return;
        }
        if (model.startsWith("gemini-3")) {
            builder.thinkingLevel(
                    "high".equals(effort)
                            ? GoogleGenAiThinkingLevel.HIGH
                            : GoogleGenAiThinkingLevel.LOW);
            return;
        }
        int budget =
                switch (effort) {
                    case "none" -> model.startsWith("gemini-2.5-pro") ? 128 : 0;
                    case "low" -> 1024;
                    case "medium" -> 8192;
                    default -> 24576; // "high"
                };
        builder.thinkingBudget(budget);
    }

    /**
     * Trims/lowercases an admin-supplied effort and returns it only if it is one of the four
     * supported values - anything else (including null) comes back null so callers send NOTHING
     * rather than an invented value. The write path ({@code PlatformController}) validates too;
     * this is the read-side defensive twin, same posture as {@link #resolveModel}.
     */
    private static String normalizeEffort(String effort) {
        if (effort == null) {
            return null;
        }
        String normalized = effort.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "low", "medium", "high" -> normalized;
            default -> null;
        };
    }
}
