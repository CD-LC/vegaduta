package com.agenticai.core.imagegen;

import com.agenticai.core.providers.ProviderName;

/**
 * One image-generation provider, selectable by a tenant's own BYOK
 * credential ({@link com.agenticai.core.providers.ProviderCredentialResolver})
 * via {@link ImageGenerationClientRegistry} - the "plug in any provider, not
 * only Vertex" surface for the authenticated creative studio. Mirrors {@code
 * core.chat.AgentChatClientFactory}'s one-implementation-per-provider
 * registry shape exactly, so adding a provider here is the same one-new-bean
 * exercise chat providers already are.
 *
 * <p>Deliberately separate from {@link GeminiImageGenerationClient} (the
 * platform-hosted-pool client the anonymous demo and hosted paths use) and
 * from {@link OpenAiImageGenerationClient} (kept exactly as-is, wrapped by
 * {@link OpenAiCreativeImageClient} below rather than modified, so the
 * existing {@code GenerateImageToolCallback} call site has zero blast
 * radius from this change).
 */
public interface ImageGenerationClient {

    ProviderName supportedProvider();

    /** @param apiKey the tenant's decrypted credential for {@link #supportedProvider()} - a plain key for most providers, an encoded {@code VertexCredential} blob for {@code GOOGLE_VERTEX}. */
    GeneratedImage generate(String apiKey, String prompt, String model);

    record GeneratedImage(byte[] bytes, String contentType, String model) {
    }
}
