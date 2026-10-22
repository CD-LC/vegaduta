package com.agenticai.core.chat;

import com.agenticai.core.providers.OllamaCredential;
import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Ollama exposes an OpenAI-chat-completions-compatible surface at {@code
 * {baseUrl}/v1/chat/completions}, so this reuses Spring AI's {@code spring-ai-starter-model-openai}
 * module the same way {@link OpenRouterAgentChatClientFactory} does - the one real difference is
 * that {@code baseUrl} is per-tenant (a tenant-hosted server), not a fixed constant, so it's
 * decoded per-call from the {@link OllamaCredential} JSON blob that {@code apiKey} actually carries
 * here (see that record's javadoc for the encode/decode pattern - the same "the DTO's apiKey field
 * just happens to contain a JSON blob" trick every multi-field provider in this package uses).
 *
 * <p>Auth is optional: Ollama itself has none by default. When the decoded credential's {@code
 * apiKey} is blank, {@link NoopApiKey} is used (same trick {@link SarvamAgentChatClientFactory}
 * uses for its custom-header case) so {@code OpenAiApi} sends no {@code Authorization} header at
 * all, rather than a Bearer token wrapping an empty string - confirmed via {@code OpenAiApi}'s own
 * {@code addDefaultHeadersIfMissing}, which explicitly skips setting the header when the configured
 * key is a {@code NoopApiKey} instance. When a key IS supplied (a tenant running an auth proxy in
 * front of their Ollama server), a normal Bearer token is used.
 */
@Component
public class OllamaAgentChatClientFactory implements AgentChatClientFactory {

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    private final MeterRegistry meterRegistry;
    private final LlmPricingCatalog pricingCatalog;
    // Shared across every factory in this package so no provider is left able
    // to have a turn killed by its own model's malformed or invented tool call -
    // see ResilientToolCallingManagerFactory.
    private final ResilientToolCallingManagerFactory toolCallingManagers;

    // Outbound HTTP timeouts (2026-07-29 security audit) - OpenAiApi's own
    // default RestClient/WebClient have none at all. Especially load-bearing
    // here: a self-hosted Ollama base-url pointing at a host that is simply
    // gone is the single most likely hung-upstream case in this package.
    // See LlmHttpClientFactory.
    private final LlmHttpClientFactory llmHttpClientFactory;
    // Bounded retries, real ObservationRegistry, default advisors, stream usage -
    // see LlmModelSupport.
    private final LlmModelSupport modelSupport;
    // Shared OpenAI-compatible transport/model construction (ADR-0012 P1b) - see
    // OpenAiCompatModelBuilder.
    private final OpenAiCompatModelBuilder modelBuilder;
    // Ollama's default num_ctx (~4k) silently truncates a tool-heavy prompt -
    // a 45k-token turn comes back with the system prompt evicted and the model
    // produces reasoning then an empty answer. extraBody injects
    // {"options":{"num_ctx":N}} into every request (Ollama's OpenAI-compat
    // accepts it; verified against a live server).
    private final int numCtx;
    // llama.cpp's n_batch: tokens evaluated per prompt-processing step. The
    // server default (512) makes a several-thousand-token fixed prefix the
    // dominant cost of every turn on a local model - a larger batch
    // parallelises prompt eval far better on the host GPU. 0 = leave the
    // server default (most installs should not tune this; personal/laptop
    // installs with a multi-K-token agent prefix can, e.g. 2048 - keep it
    // <= num_ctx, the runner rejects a larger batch).
    private final int numBatch;

    // Thinking models (qwen3.5 and friends) write a long hidden reasoning pass before the first
    // visible
    // word; on a laptop CPU that pass is most of the wait. Ollama's OpenAI-compat endpoint maps
    // reasoning_effort "none" to "do not think" for such models (docs/api/openai-compatibility.mdx)
    // and an
    // unsupported name falls back to the model default, so a wrong value cannot fail a call. Blank
    // = send
    // nothing and keep each model's own default; the personal local-AI overlay sets it to "none".
    @org.springframework.beans.factory.annotation.Value("${app.llm.ollama.reasoning-effort:}")
    private String reasoningEffort = "";

    public OllamaAgentChatClientFactory(
            LlmCallMetricsService llmCallMetricsService,
            LlmCallLogService llmCallLogService,
            MeterRegistry meterRegistry,
            LlmPricingCatalog pricingCatalog,
            LlmHttpClientFactory llmHttpClientFactory,
            ResilientToolCallingManagerFactory toolCallingManagers,
            LlmModelSupport modelSupport,
            @org.springframework.beans.factory.annotation.Value("${app.llm.ollama.num-ctx:32768}")
                    int numCtx,
            @org.springframework.beans.factory.annotation.Value("${app.llm.ollama.num-batch:0}")
                    int numBatch) {
        this.modelSupport = modelSupport;
        this.llmCallMetricsService = llmCallMetricsService;
        this.llmCallLogService = llmCallLogService;
        this.meterRegistry = meterRegistry;
        this.pricingCatalog = pricingCatalog;
        this.llmHttpClientFactory = llmHttpClientFactory;
        this.toolCallingManagers = toolCallingManagers;
        this.numCtx = numCtx;
        this.numBatch = numBatch;
        this.modelBuilder =
                new OpenAiCompatModelBuilder(
                        llmHttpClientFactory, toolCallingManagers, modelSupport);
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.OLLAMA;
    }

    @Override
    public ChatClient create(
            String apiKey, String model, int maxTokens, double temperature, double topP) {
        return create(apiKey, model, maxTokens, temperature, topP, java.util.Map.of());
    }

    /**
     * Applies the {@link SamplingOptions} Ollama's OpenAI-compatible endpoint documents - {@code
     * stop}, {@code seed}, {@code frequency_penalty}, {@code presence_penalty}. It has no {@code
     * top_k} there (that is a native-API option), so topK is omitted and DEBUG-logged.
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
        OllamaCredential credential = OllamaCredential.decode(apiKey);
        // SEC-023: the base URL is tenant-supplied. Checked at USE time (a row saved before the
        // guard, or a DNS record repointed since, is refused here), and the clients below are the
        // guarded ones: blocking path address-pinned + no redirects, streaming path no redirects.
        llmHttpClientFactory.requireAllowedTenantBaseUrl(normalizeBaseUrl(credential.baseUrl()));
        boolean hasKey = credential.apiKey() != null && !credential.apiKey().isBlank();

        OpenAiCompatModelBuilder.Api apiBuilder =
                modelBuilder
                        // Ollama's OpenAI-compat surface is at {baseUrl}/v1/chat/completions -
                        // baseUrl must be just the host (no trailing "/v1"), same reasoning
                        // as every other OpenAI-compatible factory in this package.
                        .api(normalizeBaseUrl(credential.baseUrl()))
                        // Ollama sends thinking as "reasoning"; Spring AI 2's OpenAI model reads
                        // both "reasoning" and "reasoning_content" natively, so no wire rewrite
                        // is needed (it was for 1.x; see OllamaWireBehaviourTest).
                        .guarded();
        apiBuilder = hasKey ? apiBuilder.apiKey(credential.apiKey()) : apiBuilder.noApiKey();

        OpenAiCompatModelBuilder.Api openAiApi = apiBuilder;

        OpenAiChatOptions.Builder optionsBuilder =
                modelBuilder
                        .options(ProviderName.OLLAMA, model)
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP);
        sampling.applyTo(optionsBuilder);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            optionsBuilder.reasoningEffort(reasoningEffort.trim());
        }
        LlmModelSupport.logDroppedSampling(ProviderName.OLLAMA, model, sampling, "topK");
        // Ollama `format` (json_schema) only when the caller asked for a schema and
        // app.llm.structured-output.ollama-format.enabled is on; otherwise options unchanged.
        OpenAiChatOptions options =
                modelSupport.applyOllamaStructuredSchema(optionsBuilder.build(), providerOptions);
        java.util.Map<String, Object> ollamaOptions = new java.util.LinkedHashMap<>();
        ollamaOptions.put("num_ctx", numCtx);
        if (numBatch > 0) {
            ollamaOptions.put("num_batch", numBatch);
        }
        options = options.mutate().extraBody(java.util.Map.of("options", ollamaOptions)).build();

        OpenAiChatModel openAiChatModel = modelBuilder.chatModel(openAiApi, options);
        // A model whose Ollama runner refuses the schema (MLX, seen with qwen3.5:4b) falls back to
        // prompt-based JSON with validation and one repair turn instead of failing the call.
        org.springframework.ai.chat.model.ChatModel chatModel = openAiChatModel;
        var schemaSpec =
                com.agenticai.core.chat.structured.StructuredSchemaSpec.from(providerOptions);
        if (schemaSpec != null && options.getResponseFormat() != null) {
            chatModel =
                    new OllamaStructuredFallbackChatModel(openAiChatModel, schemaSpec.jsonSchema());
        }

        return modelSupport
                .chatClientBuilder(chatModel, toolCallingManagers.create())
                .defaultAdvisors(
                        modelSupport.advisors(
                                new LlmMetricsAdvisor(
                                        llmCallMetricsService,
                                        llmCallLogService,
                                        ProviderName.OLLAMA,
                                        model,
                                        meterRegistry,
                                        pricingCatalog)))
                .build();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.replaceAll("/+$", "");
    }
}
