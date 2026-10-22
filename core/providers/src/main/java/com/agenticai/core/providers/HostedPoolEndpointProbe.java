package com.agenticai.core.providers;

import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Upstream HTTP for the hosted LLM pool: the {@code GET .../models} key-validation probe a save
 * runs, the live model-discovery call behind the admin form's dropdown, and the base-URL /
 * models-URL shaping both share. Split out of {@link PlatformHostedLlmProviderService}; logs under
 * that class's logger so operators keep one log category for the pool.
 */
public final class HostedPoolEndpointProbe {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformHostedLlmProviderService.class);

    private final RestClient restClient;
    private final Supplier<SsrfGuard> ssrfGuard;

    HostedPoolEndpointProbe(RestClient restClient, Supplier<SsrfGuard> ssrfGuard) {
        this.restClient = restClient;
        this.ssrfGuard = ssrfGuard;
    }

    /**
     * Strips exactly one trailing {@code /v1} (with or without a trailing slash) before persisting
     * or validating a {@code baseUrl} - {@link com.agenticai.core.chat.HostedLlmChatClientFactory}
     * (which actually builds the chat client Spring AI's {@code OpenAiApi} uses) already appends
     * its own {@code /v1/chat/completions}, so a stored {@code baseUrl} that itself ends in {@code
     * /v1} would double up into {@code .../v1/v1/chat/completions} and 404 on every real chat call
     * - the exact "provider is configured but FREE-tier chat still doesn't work" bug this
     * normalization exists to close off at the source, rather than only defensively in the
     * chat-client factory. Normalizing here (not just there) also means what an admin sees echoed
     * back in the list endpoint matches what's actually used at chat time.
     */
    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        // strip(), not stripTrailing(): a LEADING space survives stripTrailing()
        // and makes the resulting URI unparseable at request time. The DTO's
        // compact constructor already trims, this is the belt-and-braces half for
        // any caller that builds the request object directly.
        String trimmed = baseUrl.strip();
        if (trimmed.endsWith("/v1")) {
            return trimmed.substring(0, trimmed.length() - "/v1".length());
        }
        if (trimmed.endsWith("/v1/")) {
            return trimmed.substring(0, trimmed.length() - "/v1/".length());
        }
        return trimmed;
    }

    static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    /** See {@link PlatformHostedLlmProviderService#discoverModels}. */
    List<String> discoverModels(String baseUrl, String apiKey, String completionsPath) {
        String effective = effectiveBaseUrl(baseUrl, trimToNull(completionsPath));
        String path = trimToNull(completionsPath);
        String modelsUrl =
                path != null
                        ? deriveModelsUrl(effective, path)
                        : (effective != null && effective.endsWith("/")
                                ? effective + "v1/models"
                                : effective + "/v1/models");
        if (modelsUrl == null) {
            return List.of();
        }
        try {
            // SEC-023: this probe sends the typed key as a Bearer token to the
            // typed URL - same guard as a save, fail soft like every other path here.
            HostedLlmBaseUrlGuard.checkAllowed(effective, ssrfGuard.get());
        } catch (ProviderValidationException e) {
            log.warn("Live model discovery refused by the SSRF guard: {}", e.getMessage());
            return List.of();
        }
        try {
            JsonNode response =
                    restClient
                            .get()
                            .uri(modelsUrl)
                            .header("Authorization", "Bearer " + apiKey.trim())
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null || !response.has("data")) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode node : response.get("data")) {
                String id = node.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (RestClientException e) {
            log.warn("Live model discovery against {} failed - returning empty list", modelsUrl, e);
            return List.of();
        }
    }

    /**
     * The {@code baseUrl} shape to persist and validate against. For a conventional row ({@code
     * completionsPath == null}) this strips a trailing {@code /v1} exactly as before (see {@link
     * #normalizeBaseUrl}). For a custom-path row (e.g. Gemini) the base URL is used verbatim -
     * stripping {@code /v1} would be wrong when the whole reason for a custom path is a
     * non-standard layout, and {@link com.agenticai.core.chat.HostedLlmChatClientFactory} likewise
     * skips normalization for these rows. Surrounding whitespace is trimmed either way.
     */
    static String effectiveBaseUrl(String rawBaseUrl, String completionsPath) {
        if (rawBaseUrl == null) {
            return null;
        }
        return completionsPath != null ? rawBaseUrl.strip() : normalizeBaseUrl(rawBaseUrl);
    }

    /**
     * Derives the OpenAI-style {@code models} metadata URL for a custom-path row by swapping the
     * trailing {@code /chat/completions} segment of {@code completionsPath} for {@code /models}
     * (Gemini's OpenAI-compat surface exposes {@code /v1beta/openai/models} exactly this way).
     * Returns {@code null} - meaning "no probe possible, treat as valid" - if the path does not end
     * in {@code /chat/completions}, so an unusual custom path never blocks a save.
     */
    static String deriveModelsUrl(String baseUrl, String completionsPath) {
        String suffix = "/chat/completions";
        if (!completionsPath.endsWith(suffix)) {
            return null;
        }
        String modelsPath =
                completionsPath.substring(0, completionsPath.length() - suffix.length())
                        + "/models";
        String host = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = modelsPath.startsWith("/") ? modelsPath : "/" + modelsPath;
        return host + path;
    }

    void validateKey(String baseUrl, String apiKey, String completionsPath) {
        String modelsUrl;
        if (completionsPath != null) {
            modelsUrl = deriveModelsUrl(baseUrl, completionsPath);
            if (modelsUrl == null) {
                // No conventional models endpoint to probe for this custom path
                // - stay tolerant (skip the probe) rather than fail a save. The
                // key still gets exercised by the first real chat call.
                log.info(
                        "Skipping models probe for custom-path hosted provider"
                                + " (completionsPath={})",
                        completionsPath);
                return;
            }
        } else {
            modelsUrl = baseUrl.endsWith("/") ? baseUrl + "v1/models" : baseUrl + "/v1/models";
        }
        try {
            // A blank key (keyless local server) probes without an Authorization header.
            RestClient.RequestHeadersSpec<?> probe = restClient.get().uri(modelsUrl);
            if (StringUtils.hasText(apiKey)) {
                probe = probe.header("Authorization", "Bearer " + apiKey.trim());
            }
            probe.retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            log.warn(
                    "Hosted LLM provider validation against {} failed with HTTP {}",
                    modelsUrl,
                    status.value());
            throw new ProviderValidationException(
                    "Could not validate this key against "
                            + baseUrl
                            + " (HTTP "
                            + status.value()
                            + "). Check the base URL and key are correct - most OpenAI-compatible"
                            + " providers expose GET {baseUrl}/v1/models for exactly this check.");
        } catch (RestClientException e) {
            log.warn("Hosted LLM provider validation call to {} failed", modelsUrl, e);
            // No transport detail (refused / timed out / reset): it would tell a caller which
            // ports are open behind the URL. Logged above for operators.
            throw new ProviderValidationException(
                    "Could not reach "
                            + baseUrl
                            + " to validate this key - check the "
                            + "base URL is correct and publicly reachable.",
                    e);
        }
    }
}
