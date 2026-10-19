package com.agenticai.core.imagegen;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
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
 * Image generation against Google Gemini's native {@code generateContent}
 * endpoint, paid for with the <b>platform-held</b> Google key rather than any
 * tenant's own.
 *
 * <h2>Why this exists next to {@link OpenAiImageGenerationClient}</h2>
 * That client resolves a per-TENANT BYOK OpenAI key through {@code
 * ProviderCredentialResolver}. For the no-signup sandbox there is no tenant
 * that has activated anything and there never will be - the visitor has no
 * account, which is the whole feature. So the credential has to come from the
 * one pool the platform already pays for and already routes FREE-tier chat
 * through: {@code control_plane.platform_hosted_llm_provider}, read here via
 * {@link PlatformHostedLlmProviderService#resolveEnabledChain()} - the exact
 * same decrypt-behind-a-resolver seam the hosted chat path uses, so there is
 * no second copy of the Google key anywhere and no key in configuration.
 *
 * <h2>Why the native endpoint and not the OpenAI-compat one</h2>
 * The pool row for Gemini is configured for chat: {@code baseUrl =
 * https://generativelanguage.googleapis.com} plus {@code completionsPath =
 * /v1beta/openai/chat/completions}. That OpenAI-compatibility shim does not
 * expose image generation. The native surface -
 * {@code POST {baseUrl}/v1beta/models/{model}:generateContent} returning an
 * {@code inlineData} part - does. So this client borrows the row's HOST and
 * KEY and calls the native path itself, which is why it takes the base URL
 * from the row rather than hardcoding a host.
 *
 * <h2>Image models only</h2>
 * {@code gemini-2.5-flash-image} and siblings are billed per image. Video
 * models (Veo) are billed <b>per second of output</b> and are categorically
 * not something to put behind an unauthenticated endpoint - {@link
 * #REJECTED_MODEL_MARKERS} makes that a hard refusal rather than a comment,
 * because the model id is an operator-editable platform setting and a typo
 * there would be an extremely expensive one.
 */
@Component
public class GeminiImageGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiImageGenerationClient.class);

    /** Identifies which pool row holds a Google key. Matched on host, not label, because the label is free text an admin types. */
    private static final String GOOGLE_HOST = "generativelanguage.googleapis.com";

    /** Substrings that mark a model as video/audio generation - refused outright. See the class javadoc. */
    private static final List<String> REJECTED_MODEL_MARKERS = List.of("veo", "video", "tts", "speech", "audio");

    /**
     * Short on purpose. An anonymous visitor is watching a spinner, and an
     * image request that has not come back in 40s is not going to produce a
     * better experience than the honest sample fallback does.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(40);

    private final RestClient restClient;
    private final PlatformHostedLlmProviderService hostedProviders;

    public GeminiImageGenerationClient(PlatformHostedLlmProviderService hostedProviders) {
        this.hostedProviders = hostedProviders;
        // Own RestClient with explicit timeouts rather than the shared
        // RestClient.Builder: the shared one carries whatever defaults the app
        // sets (effectively none), and an unbounded read timeout on a path a
        // visitor is watching is the difference between a graceful sample and
        // an abandoned tab.
        //
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

    /** @param bytes decoded image bytes; {@code mimeType} as declared by the provider (the caller normalizes it before storing). */
    public record GeneratedImageBytes(byte[] bytes, String mimeType, String model) {
    }

    /** True when a Google row is present and enabled in the pool - a cheap, non-throwing pre-check for "can this feature work at all". */
    public boolean isAvailable() {
        return findGoogleRow().isPresent();
    }

    /**
     * @throws ImageGenNotConfiguredException when the pool holds no enabled
     *     Google row, or the requested model is not an image model.
     * @throws ImageGenApiException on any upstream failure, including a
     *     safety block. Both are caught by the demo controller and turned
     *     into a labelled sample - nothing from here reaches a visitor as an
     *     error.
     */
    @SuppressWarnings("unchecked")
    public GeneratedImageBytes generate(String prompt, String model) {
        requireImageModel(model);
        HostedProviderSelection selection = findGoogleRow().orElseThrow(() -> new ImageGenNotConfiguredException(
            "No platform-hosted Google provider is configured, so image generation is unavailable."
        ));

        String host = trimTrailingSlash(selection.provider().getBaseUrl());
        // The key travels as a query parameter because that is Gemini's
        // documented and verified-working form for this endpoint. Consequence,
        // handled deliberately: this URI must NEVER be logged, and it is not -
        // every log statement below prints the host and model only, and the
        // RestClientResponseException path prints the status, not the request.
        // URI.create, not the String overload: RestClient#uri(String) treats
        // its argument as a URI TEMPLATE and would try to expand any {...} in
        // it. Nothing here should contain braces, but the string carries a
        // credential and "should not" is not a good enough reason to hand it
        // to a template expander.
        URI uri;
        try {
            uri = generateContentUri(host, model, selection.decryptedApiKey());
        } catch (IllegalArgumentException e) {
            // Deliberately no cause and no message: either would carry the request URI.
            log.warn("Gemini image generation: the endpoint for model {} on {} is not a valid URL", model, host);
            throw new ImageGenApiException("Gemini image generation is misconfigured (invalid endpoint).");
        }

        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        );

        Map<String, Object> response;
        try {
            response = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            log.warn("Gemini image generation failed against {} (model {}) with HTTP {}", host, model, status.value());
            throw new ImageGenApiException("Gemini image generation failed (HTTP " + status.value() + ")");
        } catch (RestClientException e) {
            // The exception object is deliberately NOT handed to the logger.
            // A transport failure's message embeds the request URI, and the
            // URI carries the API key - Spring does strip the query string
            // when it builds that message, but "the framework probably
            // redacts it" is not an acceptable basis for writing a platform
            // credential to disk. Redacting here is unconditional, and the
            // exception type plus the message are all the diagnosis a
            // connect/read timeout actually needs.
            log.warn("Could not reach Gemini at {} for image generation (model {}): {} - {}",
                host, model, e.getClass().getSimpleName(), redact(e.getMessage()));
            throw new ImageGenApiException("Could not reach Gemini to generate this image.");
        }

        return extractImage(response, model);
    }

    // ------------------------------------------------------------------

    private Optional<HostedProviderSelection> findGoogleRow() {
        try {
            return hostedProviders.resolveEnabledChain().stream()
                .filter(s -> s.provider().getBaseUrl() != null
                    && s.provider().getBaseUrl().toLowerCase(Locale.ROOT).contains(GOOGLE_HOST))
                .findFirst();
        } catch (RuntimeException e) {
            // A decrypt failure (wrong APP_ENCRYPTION_KEY) or a DB blip must
            // not become a 500 on the demo path - report "not available" and
            // let the caller degrade.
            log.warn("Could not resolve a platform-hosted Google provider for image generation", e);
            return Optional.empty();
        }
    }

    private static void requireImageModel(String model) {
        if (model == null || model.isBlank()) {
            throw new ImageGenNotConfiguredException("No image model is configured.");
        }
        String lower = model.toLowerCase(Locale.ROOT);
        for (String marker : REJECTED_MODEL_MARKERS) {
            if (lower.contains(marker)) {
                // Loud, because this can only happen through an operator
                // mis-setting demo.image.model, and the failure it prevents is
                // a per-second billing line behind an anonymous endpoint.
                log.error("Refusing to call model '{}' from the demo image path - not an image model", model);
                throw new ImageGenNotConfiguredException("The configured demo image model is not an image model.");
            }
        }
    }

    /**
     * Digs the {@code inlineData} part out of the first candidate. Tolerates
     * both {@code inlineData} and {@code inline_data} spellings: the REST API
     * returns camelCase today, but the proto field is snake_case and some
     * gateway shims echo it through unchanged - accepting both costs one line
     * and removes a whole class of silent "generation succeeded, image
     * missing" failure.
     */
    @SuppressWarnings("unchecked")
    private static GeneratedImageBytes extractImage(Map<String, Object> response, String model) {
        if (response == null) {
            throw new ImageGenApiException("Gemini returned an empty response.");
        }
        Object feedback = response.get("promptFeedback");
        if (feedback instanceof Map<?, ?> fb && fb.get("blockReason") != null) {
            // The provider's own safety filter - the real content control that
            // DemoImagePromptGuard's keyword screen only front-runs.
            throw new ImageGenApiException("blocked:" + fb.get("blockReason"));
        }

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new ImageGenApiException("Gemini returned no candidates for this prompt.");
        }
        Object content = candidates.get(0).get("content");
        if (!(content instanceof Map<?, ?> contentMap)) {
            throw new ImageGenApiException("Gemini candidate carried no content.");
        }
        Object parts = contentMap.get("parts");
        if (!(parts instanceof List<?> partList)) {
            throw new ImageGenApiException("Gemini candidate carried no parts.");
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
                return new GeneratedImageBytes(
                    Base64.getDecoder().decode(b64),
                    mime instanceof String s ? s : "image/png",
                    model
                );
            } catch (IllegalArgumentException e) {
                throw new ImageGenApiException("Gemini returned image data that could not be decoded.");
            }
        }
        // Gemini's image models answer a refused or non-visual prompt with a
        // TEXT part instead of an image. That is a real outcome, not a bug -
        // treat it as a generation failure so the caller degrades honestly
        // rather than rendering nothing.
        throw new ImageGenApiException("Gemini returned text instead of an image for this prompt.");
    }

    /**
     * Removes anything from a {@code key=} query parameter onward. Blunt on
     * purpose: this runs on strings destined for a log file, where over-
     * redacting costs nothing and under-redacting persists a platform
     * credential.
     */
    static String redact(String message) {
        if (message == null) {
            return "";
        }
        int idx = message.indexOf("key=");
        String cut = idx < 0 ? message : message.substring(0, idx) + "key=***";
        return com.agenticai.core.security.LogMasking.secrets(cut);
    }

    /**
     * The key is percent-encoded: a stray space, quote or other character from a hand-pasted key must not make
     * {@code URI.create} throw, because that exception's message quotes the WHOLE string (key included) and the
     * global handler logs unexpected exceptions. Callers that still get an {@link IllegalArgumentException} (a bad
     * host) must not log it or chain it.
     */
    static URI generateContentUri(String host, String model, String apiKey) {
        return URI.create(host + "/v1beta/models/" + model + ":generateContent?key="
            + java.net.URLEncoder.encode(apiKey.trim(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
