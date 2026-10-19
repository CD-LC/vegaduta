package com.agenticai.core.imagegen;

import com.agenticai.core.providers.ProviderName;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin {@link ImageGenerationClient} adapter around {@link
 * OpenAiImageGenerationClient} for the new BYOK creative-studio registry -
 * composition, not modification, so the existing {@code
 * GenerateImageToolCallback} call site is untouched by this change. This
 * class owns its own {@link RestClient}/key handling rather than delegating
 * to the credential-resolving overload, since the registry already resolved
 * the tenant's active key before calling here.
 */
@Component
public class OpenAiCreativeImageClient implements ImageGenerationClient {

    private final RestClient restClient;

    public OpenAiCreativeImageClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.OPENAI;
    }

    @Override
    public GeneratedImage generate(String apiKey, String prompt, String model) {
        // No forced quality here - this registry path isn't wired to any controller yet
        // (see ImageGenerationClientRegistry's javadoc), so there's no tenant plan to gate
        // against until it is. OpenAiImageGenerationClient#generate is the one live,
        // quota-checked call site (see its javadoc for the 2026-07-27 billing-audit fix).
        OpenAiImageGenerationClient.GeneratedImageBytes result =
            OpenAiImageGenerationClient.generateWithKey(restClient, apiKey, prompt, model, null, null);
        return new GeneratedImage(result.bytes(), result.contentType(), result.model());
    }
}
