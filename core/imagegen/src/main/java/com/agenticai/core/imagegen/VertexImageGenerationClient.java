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
import java.util.Locale;
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
 * Image generation on Vertex AI, via the same {@code generateContent} shape
 * {@link GeminiImageGenerationClient} calls on the Developer API - only the
 * host, auth, and model-path differ. Two entry points: {@link #generate}
 * (BYOK, {@link ImageGenerationClient}, tenant's own credential) and {@link
 * #generateFromPool} (the platform's 3-project pool, used by the demo/FREE
 * paths - see {@link VertexPoolProperties}). Same video/audio model refusal
 * as the Gemini client - see {@link #REJECTED_MODEL_MARKERS}.
 */
@Component
public class VertexImageGenerationClient implements ImageGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(VertexImageGenerationClient.class);
    private static final List<String> REJECTED_MODEL_MARKERS = List.of("veo", "video", "tts", "speech", "audio");
    private static final Duration TIMEOUT = Duration.ofSeconds(40);

    private final VertexAccessTokenProvider tokenProvider;
    private final RestClient restClient;

    public VertexImageGenerationClient(VertexAccessTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
        // JdkClientHttpRequestFactory (java.net.http.HttpClient), not
        // SimpleClientHttpRequestFactory (java.net.HttpURLConnection): the
        // latter's getInputStream() is `synchronized` and pins the carrier
        // thread for the whole call under spring.threads.virtual.enabled.
        // Connect timeout moves to the HttpClient.Builder (the factory itself
        // has no connect-timeout setter); read timeout keeps the same
        // Duration-based setter, unchanged in behaviour.
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
    public GeneratedImage generate(String apiKey, String prompt, String model) {
        VertexCredential credential = VertexCredential.decode(apiKey);
        String token = tokenProvider.accessTokenForCredential(credential);
        return call(credential.projectId(), credential.location(), token, prompt, model);
    }

    /** Platform-pool variant for the demo/FREE paths - picks the first usable, enabled slot capable of images. */
    public Optional<GeneratedImage> generateFromPool(String prompt, String model) {
        return tokenProvider.usablePoolSlots().stream()
            .filter(VertexPoolProperties.Slot::isSupportsImage)
            .findFirst()
            .map(slot -> {
                String token = tokenProvider.accessTokenForPoolSlot(slot);
                return call(slot.getProjectId(), slot.getLocation(), token, prompt, model);
            });
    }

    public boolean isPoolAvailable() {
        return tokenProvider.usablePoolSlots().stream().anyMatch(VertexPoolProperties.Slot::isSupportsImage);
    }

    @SuppressWarnings("unchecked")
    private GeneratedImage call(String projectId, String location, String token, String prompt, String model) {
        requireImageModel(model);
        String uri = "https://" + location + "-aiplatform.googleapis.com/v1/projects/" + projectId
            + "/locations/" + location + "/publishers/google/models/" + model + ":generateContent";

        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        );

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
            HttpStatusCode status = e.getStatusCode();
            log.warn("Vertex AI image generation failed against {} (model {}) with HTTP {}", location, model, status.value());
            throw new ImageGenApiException("Vertex AI image generation failed (HTTP " + status.value() + ")");
        } catch (RestClientException e) {
            log.warn("Could not reach Vertex AI for image generation (model {}): {}", model, e.getClass().getSimpleName());
            throw new ImageGenApiException("Could not reach Vertex AI to generate this image.");
        }

        return extractImage(response, model);
    }

    private static void requireImageModel(String model) {
        if (model == null || model.isBlank()) {
            throw new ImageGenNotConfiguredException("No image model is configured.");
        }
        String lower = model.toLowerCase(Locale.ROOT);
        for (String marker : REJECTED_MODEL_MARKERS) {
            if (lower.contains(marker)) {
                log.error("Refusing to call model '{}' from the Vertex image path - not an image model", model);
                throw new ImageGenNotConfiguredException("The configured image model is not an image model.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static GeneratedImage extractImage(Map<String, Object> response, String model) {
        if (response == null) {
            throw new ImageGenApiException("Vertex AI returned an empty response.");
        }
        Object feedback = response.get("promptFeedback");
        if (feedback instanceof Map<?, ?> fb && fb.get("blockReason") != null) {
            throw new ImageGenApiException("blocked:" + fb.get("blockReason"));
        }
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new ImageGenApiException("Vertex AI returned no candidates for this prompt.");
        }
        Object content = candidates.get(0).get("content");
        if (!(content instanceof Map<?, ?> contentMap)) {
            throw new ImageGenApiException("Vertex AI candidate carried no content.");
        }
        Object parts = contentMap.get("parts");
        if (!(parts instanceof List<?> partList)) {
            throw new ImageGenApiException("Vertex AI candidate carried no parts.");
        }
        for (Object part : partList) {
            if (!(part instanceof Map<?, ?> partMap)) {
                continue;
            }
            Object inline = partMap.get("inlineData");
            if (inline == null) {
                inline = partMap.get("inline_data");
            }
            if (!(inline instanceof Map<?, ?> inlineMap)) {
                continue;
            }
            Object data = inlineMap.get("data");
            if (!(data instanceof String b64) || b64.isBlank()) {
                continue;
            }
            Object mime = inlineMap.get("mimeType") != null ? inlineMap.get("mimeType") : inlineMap.get("mime_type");
            try {
                return new GeneratedImage(Base64.getDecoder().decode(b64), mime instanceof String s ? s : "image/png", model);
            } catch (IllegalArgumentException e) {
                throw new ImageGenApiException("Vertex AI returned image data that could not be decoded.");
            }
        }
        throw new ImageGenApiException("Vertex AI returned text instead of an image for this prompt.");
    }
}
