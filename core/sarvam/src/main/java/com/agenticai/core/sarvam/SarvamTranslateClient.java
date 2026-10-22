package com.agenticai.core.sarvam;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Wraps {@code POST /translate}.
 * Two models, exposed as a parameter rather than hidden per the build
 * plan's explicit instruction: {@code mayura:v1} (12 languages, supports
 * {@code formal}/{@code modern-colloquial}/{@code classic-colloquial}
 * {@code mode} values and {@code auto} source-language detection) and
 * {@code sarvam-translate:v1} (all 22 scheduled languages of India, formal
 * mode only - {@link #DEFAULT_MODEL}, matching the product's "all 22
 * languages" framing).
 *
 * <p>Auth via {@code api-subscription-key}, same as every other Stage 3
 * client and {@code SarvamAgentChatClientFactory}/{@code
 * SarvamCredentialValidator} before it. Every call - success or failure -
 * is recorded through {@link SarvamCallLogService}; a missing/deactivated
 * credential surfaces as {@link SarvamNotConfiguredException} (thrown by
 * {@link SarvamCredentialService#resolveActiveApiKey()} before any HTTP call
 * is attempted) rather than {@link SarvamApiException} - a real, already-
 * mapped-by-{@code GlobalExceptionHandler} exception type either way, not a
 * null-pointer or a raw 403, which is the actual guarantee Stage 3's
 * definition of done cares about; the build plan's own prose just wasn't
 * precise about which of this module's two exception types would carry it
 * (same class of imprecision Stage 2's {@code ProviderCredentialResolver}
 * placement note already called out).
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamTranslateClient {

    private static final String URL = "https://api.sarvam.ai/translate";
    private static final String API_SUBSCRIPTION_KEY_HEADER = "api-subscription-key";

    /** {@code sarvam-translate:v1} - all 22 scheduled languages, formal mode only. */
    public static final String DEFAULT_MODEL = "sarvam-translate:v1";

    private final RestClient restClient;
    private final SarvamCredentialService credentialService;
    private final SarvamCallLogService callLogService;

    public SarvamTranslateClient(
        RestClient.Builder restClientBuilder,
        SarvamCredentialService credentialService,
        SarvamCallLogService callLogService
    ) {
        this.restClient = restClientBuilder.build();
        this.credentialService = credentialService;
        this.callLogService = callLogService;
    }

    public TranslationResult translate(String input, String sourceLanguageCode, String targetLanguageCode) {
        return translate(input, sourceLanguageCode, targetLanguageCode, null, null);
    }

    /**
     * @param model {@code mayura:v1} or {@code sarvam-translate:v1} - null/blank defaults to {@link #DEFAULT_MODEL}.
     * @param mode  tone/style ({@code formal}/{@code modern-colloquial}/{@code classic-colloquial}) - only honored by {@code mayura:v1}; null omits the field (Sarvam defaults to {@code formal}).
     */
    public TranslationResult translate(String input, String sourceLanguageCode, String targetLanguageCode, String model, String mode) {
        String resolvedModel = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        boolean success = false;
        String errorMessage = null;
        try {
            String apiKey = credentialService.resolveActiveApiKey();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("input", input);
            body.put("source_language_code", sourceLanguageCode);
            body.put("target_language_code", targetLanguageCode);
            body.put("model", resolvedModel);
            if (mode != null && !mode.isBlank()) {
                body.put("mode", mode);
            }
            JsonNode response = restClient.post()
                .uri(URL)
                .header(API_SUBSCRIPTION_KEY_HEADER, apiKey)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
            success = true;
            return new TranslationResult(
                response.path("translated_text").asText(null),
                response.path("source_language_code").asText(null)
            );
        } catch (RestClientResponseException e) {
            errorMessage = describeError(e);
            throw new SarvamApiException(e.getStatusCode().value(), "Sarvam translate call failed: " + errorMessage);
        } catch (RestClientException e) {
            errorMessage = e.getMessage();
            throw new SarvamApiException(0, "Could not reach Sarvam's translate endpoint: " + e.getMessage());
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            callLogService.record(SarvamCallCapability.TRANSLATE, resolvedModel, success, errorMessage, latencyMs, startedAt);
        }
    }

    static String describeError(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String detail = (body == null || body.isBlank()) ? null : body.trim();
        return "HTTP " + e.getStatusCode().value() + (detail != null ? ": " + detail : "");
    }

    public record TranslationResult(String translatedText, String sourceLanguageCode) {
    }
}
