package com.agenticai.core.sarvam;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Wraps {@code POST /text-lid} - identifies both the language (e.g. {@code hi-IN}) and script (e.g.
 * {@code Deva}) of a piece of text. No {@code model} parameter (one model, no version selection) -
 * {@link SarvamCallLog#getModel()} is always null for {@code LANGUAGE_ID} rows, same as {@link
 * SarvamTransliterationClient}.
 *
 * <p>Thinner wrapper than translate/STT/TTS per the build plan - same
 * credential-resolution/error-translation/call-logging contract as {@link SarvamTranslateClient};
 * see that class's javadoc for the {@link SarvamNotConfiguredException} vs {@link
 * SarvamApiException} note.
 */
@Component
public class SarvamLanguageIdClient {

    private static final String URL = "https://api.sarvam.ai/text-lid";
    private static final String API_SUBSCRIPTION_KEY_HEADER = "api-subscription-key";

    private final RestClient restClient;
    private final SarvamCredentialService credentialService;
    private final SarvamCallLogService callLogService;

    public SarvamLanguageIdClient(
            RestClient.Builder restClientBuilder,
            SarvamCredentialService credentialService,
            SarvamCallLogService callLogService) {
        this.restClient = restClientBuilder.build();
        this.credentialService = credentialService;
        this.callLogService = callLogService;
    }

    public LanguageIdResult identify(String input) {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        boolean success = false;
        String errorMessage = null;
        try {
            String apiKey = credentialService.resolveActiveApiKey();
            JsonNode response =
                    restClient
                            .post()
                            .uri(URL)
                            .header(API_SUBSCRIPTION_KEY_HEADER, apiKey)
                            .body(Map.of("input", input))
                            .retrieve()
                            .body(JsonNode.class);
            success = true;
            return new LanguageIdResult(
                    response.path("language_code").asString(null),
                    response.path("script_code").asString(null));
        } catch (RestClientResponseException e) {
            errorMessage = SarvamTranslateClient.describeError(e);
            throw new SarvamApiException(
                    e.getStatusCode().value(),
                    "Sarvam language identification call failed: " + errorMessage);
        } catch (RestClientException e) {
            errorMessage = e.getMessage();
            throw new SarvamApiException(
                    0,
                    "Could not reach Sarvam's language identification endpoint: " + e.getMessage());
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            callLogService.record(
                    SarvamCallCapability.LANGUAGE_ID,
                    null,
                    success,
                    errorMessage,
                    latencyMs,
                    startedAt);
        }
    }

    public record LanguageIdResult(String languageCode, String scriptCode) {}
}
