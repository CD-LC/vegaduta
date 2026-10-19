package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;

/**
 * Shared contract for a provider's prompt-caching mechanism. One
 * implementation per {@link ProviderName} that actually supports caching —
 * mirrors {@link AgentChatClientFactory}/{@code ImageGenerationClient}'s
 * "one interface, one bean" pattern in this codebase (see {@link
 * ProviderCachingRegistry}, which collects every implementation the exact
 * same way {@code ImageGenerationClientRegistry} collects {@code
 * ImageGenerationClient} beans — plain Spring {@code List<T>} injection,
 * no manual registration call).
 *
 * <p>Deliberately generic over {@code O}, the provider's own Spring AI
 * chat-options builder type (e.g. {@code AnthropicChatOptions.Builder},
 * {@code OpenAiChatOptions.Builder}) rather than some provider-agnostic
 * "cache config" shape, because there isn't a real common shape to unify
 * them into: Anthropic needs a full cache-object-lifecycle decision
 * (strategy enum, TTL, multi-block system caching — see {@link
 * AnthropicProviderCachingStrategy}, and the shape a real cache-object
 * provider like Gemini will need too); OpenAI is a single opaque routing
 * hint with no lifecycle at all (see {@link OpenAIProviderCachingStrategy}).
 * Forcing both through one non-generic {@code apply(SomeCacheConfig)} would
 * either lose Anthropic's real config surface or make OpenAI's implementation
 * pretend to have lifecycle it doesn't. Each {@code AgentChatClientFactory}
 * already builds a provider-specific options builder before calling {@link
 * #apply}, so the generic type is always known at the one real call site —
 * this costs nothing there and buys every implementation its own honest
 * shape.
 *
 * <p>A provider with no caching support at all simply has no implementation
 * registered — {@link ProviderCachingRegistry#isEnabled} returns {@code
 * false} for anything unregistered, so callers (including a future
 * cost-transparency panel) never need a "not supported" special case.
 */
public interface ProviderCachingStrategy<O> {

    /** The provider this strategy applies to. One implementation per provider. */
    ProviderName supportedProvider();

    /**
     * Whether caching is currently supported AND enabled for the given
     * model. Implementations that don't gate by model (both current ones
     * don't — see each class) may ignore {@code model} entirely; it's part
     * of the contract because a future provider (or a future revision of
     * either existing one) plausibly does gate by model. Pass {@code null}
     * to ask "is caching enabled for this provider at all, ignoring any
     * model-level gating" — this is what {@link
     * ProviderCachingRegistry#activeProviders()} uses for enumeration.
     */
    boolean isEnabled(String model);

    /**
     * Applies this provider's caching mechanism to an in-progress
     * options builder, returning it (fluent, same instance) for the caller
     * to continue configuring. Implementations that are disabled (per
     * {@link #isEnabled}) MUST return {@code optionsBuilder} unchanged
     * rather than requiring every caller to guard the call with an
     * {@code isEnabled} check first.
     */
    O apply(O optionsBuilder, ProviderCachingContext context);
}
