package com.agenticai.core.imagegen;

import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls OpenAI's Images API (https://api.openai.com/v1/images/generations)
 * directly via a plain {@link RestClient} - the same "no dedicated Spring AI
 * starter for this, call the REST endpoint directly" shape {@code
 * OpenAIProviderValidator} uses for key validation, applied here to image
 * generation. Reuses whatever OpenAI BYOK key the tenant already activated
 * for chat via {@link ProviderCredentialResolver#resolveActiveKey}, which
 * throws {@code ProviderNotActiveException} (already mapped by {@code
 * GlobalExceptionHandler} to 400) if nothing is active - callers don't need
 * their own "not configured" check on top of that for this specific
 * failure mode, only {@link GenerateImageToolCallback}'s tool-availability
 * gate in {@code AgentToolProvider} needs a cheap, non-throwing pre-check
 * (see that class's javadoc).
 *
 * <p>{@code gpt-image-1} (the default model) always returns base64-encoded
 * bytes with no {@code response_format} parameter accepted at all; older
 * {@code dall-e-3}/{@code dall-e-2} models default to a URL response unless
 * {@code response_format: "b64_json"} is explicitly requested - this client
 * always requests base64 for non-gpt-image-1 models too, so the result is
 * always raw bytes handed to {@link GeneratedImageService#store}, never a
 * third-party URL this app would have to re-fetch or expose directly.
 *
 * <p>Billing audit finding (2026-07-27): this was the one live, tenant-billed
 * call path with zero plan gating - {@link #generate} now resolves the
 * caller's {@link TenantPlan} the exact way {@code
 * VideoGenerationJobService#enqueue} already does (via {@link TenantContext}
 * + {@link TenantLimits}) and runs it through {@link
 * ImageGenerationQuotaService#checkAllowed} before spending a single call
 * against OpenAI, and forces {@code quality: low} for FREE-plan {@code
 * gpt-image-1} requests via {@link ImageGenerationQuotaService#forcedQualityFor}.
 * {@link #generateWithKey} itself stays plan-agnostic - it is also the seam
 * {@link OpenAiCreativeImageClient} uses, and that BYOK creative-studio path
 * is not yet wired to any controller, so there is nothing to gate there yet.
 */
@Component
public class OpenAiImageGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageGenerationClient.class);
    private static final String IMAGES_URL = "https://api.openai.com/v1/images/generations";
    private static final String DEFAULT_MODEL = "gpt-image-1";
    private static final String DEFAULT_SIZE = "1024x1024";

    private final RestClient restClient;
    private final ProviderCredentialResolver credentialResolver;
    private final ImageGenerationQuotaService quotaService;
    private final TenantLimits tenantLimits;

    public OpenAiImageGenerationClient(
        RestClient.Builder restClientBuilder,
        ProviderCredentialResolver credentialResolver,
        ImageGenerationQuotaService quotaService,
        TenantLimits tenantLimits
    ) {
        this.restClient = restClientBuilder.build();
        this.credentialResolver = credentialResolver;
        this.quotaService = quotaService;
        this.tenantLimits = tenantLimits;
    }

    public record GeneratedImageBytes(byte[] bytes, String contentType, String model, String size) {
    }

    /** @throws ImageGenerationQuotaExceededException before anything is spent, if this request would exceed the tenant's tier. */
    public GeneratedImageBytes generate(String prompt, String requestedModel, String requestedSize) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        quotaService.checkAllowed(plan);

        String apiKey = credentialResolver.resolveActiveKey(ProviderName.OPENAI);
        return generateWithKey(restClient, apiKey, prompt, requestedModel, requestedSize, quotaService.forcedQualityFor(plan));
    }

    /**
     * Same call, with the key supplied directly rather than resolved from
     * {@link com.agenticai.core.providers.ProviderCredentialResolver} - the
     * seam {@link OpenAiCreativeImageClient} uses, since the new BYOK
     * creative-studio registry resolves the tenant's active key itself
     * before dispatching by provider. {@code forcedQuality} is only honored
     * for the default {@code gpt-image-1} model (the only one whose {@code
     * quality} enum includes {@code low}/{@code medium}/{@code high}/{@code
     * auto} - {@code dall-e-3}'s {@code standard}/{@code hd} values don't
     * overlap, so a forced value is silently ignored for other models rather
     * than sent as an invalid parameter).
     */
    @SuppressWarnings("unchecked")
    static GeneratedImageBytes generateWithKey(
        RestClient restClient, String apiKey, String prompt, String requestedModel, String requestedSize, String forcedQuality
    ) {
        String model = (requestedModel == null || requestedModel.isBlank()) ? DEFAULT_MODEL : requestedModel;
        String size = (requestedSize == null || requestedSize.isBlank()) ? DEFAULT_SIZE : requestedSize;

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("size", size);
        if (!"gpt-image-1".equals(model)) {
            body.put("response_format", "b64_json");
        } else if (forcedQuality != null && !forcedQuality.isBlank()) {
            body.put("quality", forcedQuality);
        }

        try {
            Map<String, Object> response = restClient.post()
                .uri(IMAGES_URL)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) {
                throw new ImageGenApiException("OpenAI returned no image data for this prompt");
            }
            String b64 = (String) data.get(0).get("b64_json");
            if (b64 == null || b64.isBlank()) {
                throw new ImageGenApiException("OpenAI response did not include base64 image data");
            }
            byte[] bytes = Base64.getDecoder().decode(b64);
            return new GeneratedImageBytes(bytes, "image/png", model, size);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String detail = e.getResponseBodyAsString();
            log.warn("OpenAI image generation call failed with status {}: {}", status.value(), detail);
            throw new ImageGenApiException(
                "OpenAI image generation failed (HTTP " + status.value() + ")"
                    + (detail == null || detail.isBlank() ? "." : ": " + detail.trim()));
        } catch (RestClientException e) {
            log.warn("OpenAI image generation call failed", e);
            throw new ImageGenApiException("Could not reach OpenAI to generate this image: " + e.getMessage(), e);
        }
    }
}
