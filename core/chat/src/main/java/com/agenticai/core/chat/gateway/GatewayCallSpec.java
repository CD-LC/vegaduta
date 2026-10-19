package com.agenticai.core.chat.gateway;

import com.agenticai.core.providers.ProviderName;

/**
 * #848 (LLM gateway seam, in-house) - the unified request shape every call
 * into {@link LlmGatewayService} uses, instead of each caller passing the
 * same six positional args straight to {@link com.agenticai.core.chat.ChatClientRegistry#createClient}
 * the way {@code AgentChatService}/{@code AssistService}/{@code
 * GenerationParamsInferenceService}/{@code WorkflowGraphGeneratorService} all
 * used to. Deliberately still resolves to a Spring AI {@link
 * org.springframework.ai.chat.client.ChatClient} underneath (see {@link
 * LlmGatewayService}'s javadoc for why a full hand-rolled-HTTP replacement of
 * Spring AI wasn't the right tradeoff here) - this record is the "one shape
 * every caller funnels through" seam, not a wire-protocol reinvention.
 *
 * <p>{@code featureKey} (AI Model Control settings page) - which {@code
 * AiModelFeature} (core/tenancy) this call belongs to, for {@link
 * LlmGatewayService#resolveClient} to look up a tenant-configured model
 * override for. Deliberately a plain {@code String}, not the enum itself -
 * core/chat already depends on core/tenancy (see chat/pom.xml), but keeping
 * this record's own field untyped-string avoids every caller needing an
 * extra import for what is, from their side, just a label. {@code null}
 * (every existing call site, via the six-arg constructor below) resolves to
 * {@code AiModelFeature#DEFAULT} inside {@code LlmGatewayService} - so a
 * tenant's DEFAULT override already takes effect for every call in this
 * codebase without any of those call sites being touched; naming a more
 * specific feature key here is opt-in, forward-compatible plumbing for
 * whichever call site's owning module adopts it next.
 */
public record GatewayCallSpec(
    ProviderName providerName,
    String apiKey,
    String model,
    int maxTokens,
    double temperature,
    double topP,
    String featureKey,
    java.util.Map<String, Object> providerOptions
) {

    /**
     * Provider-specific per-call extras (2026-09-23, xAI Agent Tools API) - forwarded
     * verbatim to the resolved {@code AgentChatClientFactory}'s options overload; factories
     * that own no keys ignore it. Normalized to an empty immutable map when null so
     * downstream readers never null-check.
     */
    public GatewayCallSpec {
        providerOptions = providerOptions == null ? java.util.Map.of() : java.util.Map.copyOf(providerOptions);
    }

    /**
     * Six-arg convenience constructor - every call site that existed before
     * the {@code featureKey} component was added keeps compiling unchanged,
     * resolving to {@code AiModelFeature#DEFAULT} in {@link LlmGatewayService}.
     */
    public GatewayCallSpec(ProviderName providerName, String apiKey, String model, int maxTokens, double temperature, double topP) {
        this(providerName, apiKey, model, maxTokens, temperature, topP, null, null);
    }

    /**
     * Seven-arg convenience constructor - same for call sites added while {@code
     * featureKey} was the last component, before {@code providerOptions} existed.
     */
    public GatewayCallSpec(
        ProviderName providerName, String apiKey, String model, int maxTokens, double temperature, double topP,
        String featureKey
    ) {
        this(providerName, apiKey, model, maxTokens, temperature, topP, featureKey, null);
    }
}
