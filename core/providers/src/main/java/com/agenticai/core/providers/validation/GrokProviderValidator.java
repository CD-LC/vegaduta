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
 * Validates an xAI (Grok) API key with a call to GET /v1/models - xAI's API
 * is OpenAI-chat-completions-compatible (see {@code GrokAgentChatClientFactory}),
 * and {@code /v1/models} is the same free, non-billed metadata endpoint
 * OpenAI-style APIs conventionally expose for exactly this "is this key
 * valid" check.
 *
 * <p>Unlike most OpenAI-compatible providers, xAI's own status-code
 * reference (docs.x.ai/developers/debugging) documents 400 Bad Request -
 * not just 401/403 - as one of the causes for "an incorrect API key was
 * supplied" on every endpoint, GET routes included. Confirmed in production:
 * a bad Grok key surfaced as a plain HTTP 400, which the original 401/403-only
 * check missed, so it fell into the generic "try again in a moment" fallback
 * instead of the clear "key rejected" message - misleading, since the
 * problem isn't transient. 400 is now treated the same as 401/403 here, and
 * the real xAI error body (when present) is included instead of just the
 * bare status code, since a 400 can also mean something other than a bad key
 * (e.g. a malformed request) and the body says which.
 *
 * <p>Direct feedback after chasing one of these down: "there was no amount
 * available so thats why its not working" - a key with zero credits/no
 * billing method on the xAI account gets rejected with the exact same
 * {@code "Incorrect API key provided"} body as an actually-wrong key, on
 * this same free/non-billed {@code /v1/models} endpoint. There's no way to
 * tell the two apart from the response alone, so the message below names
 * both possibilities explicitly instead of only suggesting the key is
 * wrong.
 */
@Component
public class GrokProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(GrokProviderValidator.class);
    private static final String MODELS_URL = "https://api.x.ai/v1/models";

    private final RestClient restClient;

    public GrokProviderValidator(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.GROK;
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
                    "xAI rejected this API key (HTTP " + status.value() + ")"
                        + (detail != null ? ": " + detail : ".")
                        + " This means either the key itself is wrong, or the key is correct but the "
                        + "xAI account behind it has no billing method / credit balance - xAI returns "
                        + "the same error for both. Check the key, and check "
                        + "https://console.x.ai for an active payment method and available credit.");
            }
            log.warn("xAI validation call returned unexpected status {}", status.value());
            throw new ProviderValidationException(
                "Could not validate this key against xAI right now (HTTP " + status.value() + ")"
                    + (detail != null ? ": " + detail : ".") + " Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("xAI validation call failed", e);
            throw new ProviderValidationException(
                "Could not reach xAI to validate this key: " + e.getMessage(), e);
        }
    }
}
