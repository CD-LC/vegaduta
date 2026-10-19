package com.agenticai.core.chat;

/**
 * Per-call context handed to {@link ProviderCachingStrategy#apply}. Kept as
 * one small record rather than widening {@code apply}'s parameter list
 * directly, so a future strategy that needs one more piece of ambient
 * context (a tenant id, say) extends this record instead of changing every
 * existing implementation's method signature.
 *
 * @param apiKey the caller-supplied BYOK key for this request — e.g.
 *               {@link OpenAIProviderCachingStrategy} hashes this into
 *               OpenAI's {@code prompt_cache_key} routing hint since it has
 *               no tenant/agent id in scope otherwise.
 * @param model  the model id for this request.
 */
public record ProviderCachingContext(String apiKey, String model) {
}
