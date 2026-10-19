package com.agenticai.core.providers.validation;

import com.agenticai.core.providers.ProviderName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Validates a Meta Model API key ({@code api.meta.ai}, the Muse Spark
 * family - added 2026-09-17) with a call to GET /v1/models. Meta's API is
 * OpenAI-chat-completions-compatible (see {@code MetaAgentChatClientFactory})
 * and {@code /v1/models} is the same free, non-billed metadata endpoint
 * OpenAI-style APIs conventionally expose for exactly this "is this key
 * valid" check. Auth is a plain {@code Authorization: Bearer <key>} header;
 * keys look like {@code LLM|607358788850350|...} - the pipes are part of the
 * key, not a copy-paste artifact.
 *
 * <p>Same shape as {@link GrokProviderValidator}, including its 400-is-a-
 * rejection posture: 400/401/403 are all reported as "key rejected" (with
 * the upstream body folded in, since a 400 can also mean a malformed
 * request and the body says which), anything else is "try again". Meta's
 * exact status code for a bad key was not observed at authoring time, so
 * treating 400 the way xAI needed costs nothing and avoids a misleading
 * "transient" message if Meta turns out to do the same.
 *
 * <p>Keys are created at dev.meta.ai (API keys tab, "Create API key"). Meta
 * Model API sign-up is not available in every country yet - a key obtained
 * anywhere works from anywhere, and tenants who can't sign up already have
 * Muse Spark via OpenRouter as {@code meta/muse-spark-1.3}; the error copy
 * below points at both so a region-blocked tenant isn't left guessing.
 */
@Component
public class MetaProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(MetaProviderValidator.class);
    private static final String MODELS_URL = "https://api.meta.ai/v1/models";

    private final RestClient restClient;

    public MetaProviderValidator(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.META;
    }

    @Override
    public void validate(String apiKey) {
        try {
            restClient.get()
                .uri(MODELS_URL)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String body = e.getResponseBodyAsString();
            String detail = (body == null || body.isBlank()) ? null : body.trim();

            if (status.value() == 400 || status.value() == 401 || status.value() == 403) {
                throw new ProviderValidationException(
                    "Meta rejected this API key (HTTP " + status.value() + ")"
                        + (detail != null ? ": " + detail : ".")
                        + " Check the key: create or copy one at https://dev.meta.ai (API keys tab -> "
                        + "Create API key); Meta keys look like \"LLM|...|...\" and the pipes are part of the key. "
                        + "Note that Meta Model API sign-up is not yet available in every country - a key obtained "
                        + "anywhere works from anywhere, and Muse Spark is also available through OpenRouter as "
                        + "meta/muse-spark-1.3 if you cannot sign up directly.");
            }
            log.warn("Meta validation call returned unexpected status {}", status.value());
            throw new ProviderValidationException(
                "Could not validate this key against Meta right now (HTTP " + status.value() + ")"
                    + (detail != null ? ": " + detail : ".") + " Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("Meta validation call failed", e);
            throw new ProviderValidationException(
                "Could not reach Meta to validate this key: " + e.getMessage(), e);
        }
    }
}
