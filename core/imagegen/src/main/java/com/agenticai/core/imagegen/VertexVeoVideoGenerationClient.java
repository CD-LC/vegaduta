package com.agenticai.core.imagegen;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.VertexAccessTokenProvider;
import com.agenticai.core.providers.VertexCredential;
import com.agenticai.core.providers.VertexPoolProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Veo video generation on Vertex AI - {@code :predictLongRunning} to submit,
 * {@code :fetchPredictOperation} to poll, the long-running-operation shape
 * Vertex uses for anything too slow for a normal synchronous predict call.
 *
 * <p><b>Confidence note, stated honestly:</b> the request/response field
 * names below ({@code instances}/{@code parameters}/{@code
 * bytesBase64Encoded}) follow Vertex's documented Veo shape as of this
 * writing, but were not verified against a live call (no credentials
 * configured yet - see {@code VertexPoolProperties}). Re-check field names
 * against current Vertex AI Veo docs before flipping {@code
 * video.generation.enabled} on in production; the operation-id/job-status
 * plumbing around this class does not depend on the exact field names being
 * perfect, so a mismatch here surfaces as a clean {@link ImageGenApiException}
 * on first real use, not a silent wrong result.
 *
 * <p>The same confidence note applies, doubly, to the optional {@code
 * instances[].image} field added for image-to-video (first-frame
 * conditioning): Vertex's documented Veo shape for this is {@code
 * image.bytesBase64Encoded} + {@code image.mimeType}, mirroring the response
 * shape this class already parses, but it is equally unverified against a
 * live call. This is the mechanism used to keep a generated clip close to an
 * uploaded photo's actual likeness rather than reinterpreting the scene from
 * the prompt alone - it anchors the model's first frame, it does not
 * guarantee pixel-identical likeness is held for the full clip duration (no
 * current text/image-conditioned video model promises that).
 */
@Component
public class VertexVeoVideoGenerationClient implements VideoGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(VertexVeoVideoGenerationClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final VertexAccessTokenProvider tokenProvider;
    private final RestClient restClient;

    public VertexVeoVideoGenerationClient(VertexAccessTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
        // JdkClientHttpRequestFactory (java.net.http.HttpClient), not
        // SimpleClientHttpRequestFactory (java.net.HttpURLConnection): the
        // latter's getInputStream() is `synchronized` and blocks inside the
        // monitor, which pins the carrier thread for the whole call now that
        // spring.threads.virtual.enabled runs every request on a virtual
        // thread. This client is the worst offender - Veo generation runs
        // minutes-scale, so a pinned carrier here is a carrier gone for
        // minutes. Connect timeout moves to the HttpClient.Builder (the
        // factory itself has no connect-timeout setter); read timeout keeps
        // the same Duration-based setter, unchanged in behaviour.
        HttpClient httpClient = GuardedJdkHttpClient.defaults(Duration.ofSeconds(10), TIMEOUT, false);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.GOOGLE_VERTEX;
    }

    @Override
    public String submit(String apiKey, String prompt, String model, int durationSeconds, SourceImage sourceImage) {
        VertexCredential credential = VertexCredential.decode(apiKey);
        String token = tokenProvider.accessTokenForCredential(credential);
        return submitCall(credential.projectId(), credential.location(), token, prompt, model, durationSeconds, sourceImage);
    }

    @Override
    public Optional<GeneratedVideo> poll(String apiKey, String operationId, String model) {
        VertexCredential credential = VertexCredential.decode(apiKey);
        String token = tokenProvider.accessTokenForCredential(credential);
        return pollCall(credential.projectId(), credential.location(), token, operationId, model);
    }

    /** Platform-pool variant - picks the first usable, video-capable slot. Encodes the chosen slot's identity into the returned operation id so {@link #pollFromPool} can find it again without a second lookup. */
    public Optional<String> submitFromPool(String prompt, String model, int durationSeconds, SourceImage sourceImage) {
        return tokenProvider.usablePoolSlots().stream()
            .filter(VertexPoolProperties.Slot::isSupportsVideo)
            .findFirst()
            .map(slot -> {
                String token = tokenProvider.accessTokenForPoolSlot(slot);
                String opName = submitCall(slot.getProjectId(), slot.getLocation(), token, prompt, model, durationSeconds, sourceImage);
                return slot.getProjectId() + "|" + slot.getLocation() + "|" + opName;
            });
    }

    public Optional<GeneratedVideo> pollFromPool(String encodedOperationId, String model) {
        String[] parts = encodedOperationId.split("\\|", 3);
        if (parts.length != 3) {
            throw new ImageGenApiException("Malformed pool video operation id.");
        }
        String projectId = parts[0];
        String location = parts[1];
        String opName = parts[2];
        VertexPoolProperties.Slot slot = tokenProvider.usablePoolSlots().stream()
            .filter(s -> s.getProjectId().equals(projectId) && s.getLocation().equals(location))
            .findFirst()
            .orElseThrow(() -> new ImageGenApiException("The Vertex project that started this video generation is no longer configured."));
        String token = tokenProvider.accessTokenForPoolSlot(slot);
        return pollCall(projectId, location, token, opName, model);
    }

    public boolean isPoolAvailable() {
        return tokenProvider.usablePoolSlots().stream().anyMatch(VertexPoolProperties.Slot::isSupportsVideo);
    }

    // ------------------------------------------------------------------

    private String submitCall(
        String projectId, String location, String token, String prompt, String model,
        int durationSeconds, SourceImage sourceImage
    ) {
        String uri = "https://" + location + "-aiplatform.googleapis.com/v1/projects/" + projectId
            + "/locations/" + location + "/publishers/google/models/" + model + ":predictLongRunning";

        Map<String, Object> instance = sourceImage == null
            ? Map.of("prompt", prompt)
            : Map.of(
                "prompt", prompt,
                "image", Map.of(
                    "bytesBase64Encoded", sourceImage.bytesBase64(),
                    "mimeType", sourceImage.mimeType()
                )
            );
        Map<String, Object> body = Map.of(
            "instances", List.of(instance),
            "parameters", Map.of("durationSeconds", durationSeconds, "sampleCount", 1)
        );

        try {
            Map<String, Object> response = restClient.post()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
            Object name = response == null ? null : response.get("name");
            if (!(name instanceof String opName) || opName.isBlank()) {
                throw new ImageGenApiException("Vertex AI did not return an operation id for this video request.");
            }
            return opName;
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            log.warn("Vertex AI Veo submit failed against {} (model {}) with HTTP {}", location, model, status.value());
            throw new ImageGenApiException("Vertex AI video generation failed to start (HTTP " + status.value() + ")");
        } catch (RestClientException e) {
            log.warn("Could not reach Vertex AI to submit video generation (model {})", model);
            throw new ImageGenApiException("Could not reach Vertex AI to start this video.");
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<GeneratedVideo> pollCall(String projectId, String location, String token, String operationName, String model) {
        String uri = "https://" + location + "-aiplatform.googleapis.com/v1/projects/" + projectId
            + "/locations/" + location + "/publishers/google/models/" + model + ":fetchPredictOperation";

        Map<String, Object> body = Map.of("operationName", operationName);

        Map<String, Object> response;
        try {
            response = restClient.post()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        } catch (RestClientResponseException e) {
            log.warn("Vertex AI Veo poll failed with HTTP {}", e.getStatusCode().value());
            throw new ImageGenApiException("Vertex AI video status check failed (HTTP " + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            log.warn("Could not reach Vertex AI to poll video generation status");
            throw new ImageGenApiException("Could not reach Vertex AI to check this video's status.");
        }

        if (response == null || !Boolean.TRUE.equals(response.get("done"))) {
            return Optional.empty();
        }
        Object error = response.get("error");
        if (error != null) {
            throw new ImageGenApiException("Vertex AI reported a video generation failure: " + error);
        }
        Object resp = response.get("response");
        if (!(resp instanceof Map<?, ?> respMap)) {
            throw new ImageGenApiException("Vertex AI reported completion with no video payload.");
        }
        Object videos = respMap.get("videos");
        if (!(videos instanceof List<?> videoList) || videoList.isEmpty() || !(videoList.get(0) instanceof Map<?, ?> first)) {
            throw new ImageGenApiException("Vertex AI reported completion with no video in the payload.");
        }
        Object b64 = first.get("bytesBase64Encoded");
        if (!(b64 instanceof String encoded) || encoded.isBlank()) {
            throw new ImageGenApiException("Vertex AI did not return inline video bytes for this request.");
        }
        String mimeType = first.get("mimeType") instanceof String s ? s : "video/mp4";
        try {
            return Optional.of(new GeneratedVideo(Base64.getDecoder().decode(encoded), mimeType));
        } catch (IllegalArgumentException e) {
            throw new ImageGenApiException("Vertex AI returned video data that could not be decoded.");
        }
    }
}
