package com.agenticai.core.sarvam;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Wraps {@code POST /speech-to-text} (multipart file upload). Two models: {@code saarika:v2.5}
 * (legacy, 12 languages, transcribe-only) and {@link #DEFAULT_MODEL} ({@code saaras:v3}, all 23
 * languages, plus a {@code mode} parameter worth exposing directly - {@code transcribe}/{@code
 * translate}/{@code verbatim}/{@code translit}/{@code codemix}. {@code mode=translate} alone is
 * useful enough to call out per the build plan: it means "Hindi voice note in, English text out" in
 * one call rather than STT-then-translate as two separate calls.
 *
 * <p>{@code languageCode} defaults to {@code null} (omitted) here, which Sarvam interprets as
 * auto-detect for {@code saaras:v3} - matches Stage 5's planned omnichannel usage ("language
 * auto-detected via {@code language_code=unknown}"), though passing an explicit BCP-47 code (e.g.
 * {@code hi-IN}) skips detection and is faster/more accurate when the caller already knows the
 * language.
 *
 * <p>Same credential-resolution/error-translation/call-logging contract as {@link
 * SarvamTranslateClient} - see that class's javadoc for the {@link SarvamNotConfiguredException} vs
 * {@link SarvamApiException} note.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamSpeechToTextClient {

    private static final String URL = "https://api.sarvam.ai/speech-to-text";
    private static final String API_SUBSCRIPTION_KEY_HEADER = "api-subscription-key";

    /** {@code saaras:v3} - all 23 languages, supports the {@code mode} parameter. */
    public static final String DEFAULT_MODEL = "saaras:v3";

    private final RestClient restClient;
    private final SarvamCredentialService credentialService;
    private final SarvamCallLogService callLogService;

    public SarvamSpeechToTextClient(
            RestClient.Builder restClientBuilder,
            SarvamCredentialService credentialService,
            SarvamCallLogService callLogService) {
        this.restClient = restClientBuilder.build();
        this.credentialService = credentialService;
        this.callLogService = callLogService;
    }

    public TranscriptionResult transcribe(byte[] audioBytes, String filename) {
        return transcribe(audioBytes, filename, null, null, null);
    }

    /**
     * @param model {@code saaras:v3} or {@code saarika:v2.5} - null/blank defaults to {@link
     *     #DEFAULT_MODEL}.
     * @param mode only honored by {@code saaras:v3}: {@code transcribe}/{@code translate}/{@code
     *     verbatim}/{@code translit}/{@code codemix}. Null omits the field (Sarvam defaults to
     *     {@code transcribe}).
     * @param languageCode BCP-47 code (e.g. {@code hi-IN}) or {@code unknown} for auto-detect. Null
     *     omits the field.
     */
    public TranscriptionResult transcribe(
            byte[] audioBytes, String filename, String model, String mode, String languageCode) {
        String resolvedModel = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        boolean success = false;
        String errorMessage = null;
        try {
            String apiKey = credentialService.resolveActiveApiKey();
            String resolvedFilename = (filename == null || filename.isBlank()) ? "audio" : filename;
            ByteArrayResource fileResource =
                    new ByteArrayResource(audioBytes) {
                        @Override
                        public String getFilename() {
                            return resolvedFilename;
                        }
                    };

            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("file", fileResource);
            parts.add("model", resolvedModel);
            if (mode != null && !mode.isBlank()) {
                parts.add("mode", mode);
            }
            if (languageCode != null && !languageCode.isBlank()) {
                parts.add("language_code", languageCode);
            }

            JsonNode response =
                    restClient
                            .post()
                            .uri(URL)
                            .header(API_SUBSCRIPTION_KEY_HEADER, apiKey)
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(parts)
                            .retrieve()
                            .body(JsonNode.class);
            success = true;
            return new TranscriptionResult(
                    response.path("transcript").asString(null),
                    response.path("language_code").asString(null),
                    response.path("request_id").asString(null));
        } catch (RestClientResponseException e) {
            errorMessage = SarvamTranslateClient.describeError(e);
            throw new SarvamApiException(
                    e.getStatusCode().value(),
                    "Sarvam speech-to-text call failed: " + errorMessage);
        } catch (RestClientException e) {
            errorMessage = e.getMessage();
            throw new SarvamApiException(
                    0, "Could not reach Sarvam's speech-to-text endpoint: " + e.getMessage());
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            callLogService.record(
                    SarvamCallCapability.SPEECH_TO_TEXT,
                    resolvedModel,
                    success,
                    errorMessage,
                    latencyMs,
                    startedAt);
        }
    }

    public record TranscriptionResult(String transcript, String languageCode, String requestId) {}
}
