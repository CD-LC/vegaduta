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
 * Wraps {@code POST /transliterate} - converts script while preserving pronunciation (e.g. {@code
 * नमस्ते} -> {@code namaste}), distinct from {@link SarvamTranslateClient} which converts meaning.
 * No {@code model} parameter exists for this endpoint (one model, no version selection) - {@link
 * SarvamCallLog#getModel()} is always null for {@code TRANSLITERATION} rows, same as {@link
 * SarvamLanguageIdClient}.
 *
 * <p>Thinner wrapper than translate/STT/TTS per the build plan ("thinner wrappers, same pattern,
 * lower priority... but cheap to build alongside them since they share all the same plumbing") -
 * same credential- resolution/error-translation/call-logging contract as {@link
 * SarvamTranslateClient}; see that class's javadoc for the {@link SarvamNotConfiguredException} vs
 * {@link SarvamApiException} note.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamTransliterationClient {

    private static final String URL = "https://api.sarvam.ai/transliterate";
    private static final String API_SUBSCRIPTION_KEY_HEADER = "api-subscription-key";

    private final RestClient restClient;
    private final SarvamCredentialService credentialService;
    private final SarvamCallLogService callLogService;

    public SarvamTransliterationClient(
            RestClient.Builder restClientBuilder,
            SarvamCredentialService credentialService,
            SarvamCallLogService callLogService) {
        this.restClient = restClientBuilder.build();
        this.credentialService = credentialService;
        this.callLogService = callLogService;
    }

    public TransliterationResult transliterate(
            String input, String sourceLanguageCode, String targetLanguageCode) {
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
            JsonNode response =
                    restClient
                            .post()
                            .uri(URL)
                            .header(API_SUBSCRIPTION_KEY_HEADER, apiKey)
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);
            success = true;
            return new TransliterationResult(
                    response.path("transliterated_text").asText(null),
                    response.path("source_language_code").asText(null));
        } catch (RestClientResponseException e) {
            errorMessage = SarvamTranslateClient.describeError(e);
            throw new SarvamApiException(
                    e.getStatusCode().value(),
                    "Sarvam transliteration call failed: " + errorMessage);
        } catch (RestClientException e) {
            errorMessage = e.getMessage();
            throw new SarvamApiException(
                    0, "Could not reach Sarvam's transliteration endpoint: " + e.getMessage());
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            callLogService.record(
                    SarvamCallCapability.TRANSLITERATION,
                    null,
                    success,
                    errorMessage,
                    latencyMs,
                    startedAt);
        }
    }

    public record TransliterationResult(String transliteratedText, String sourceLanguageCode) {}
}
