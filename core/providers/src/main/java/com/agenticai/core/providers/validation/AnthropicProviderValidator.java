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
 * Validates an Anthropic API key with a call to GET /v1/models — this is a
 * metadata call (not a message generation call), so validating a key does
 * not spend the user's token budget.
 *
 * <p>Deliberately does NOT use Spring AI's AnthropicApi/ChatModel here: those
 * are for the agent loop (Stage 3+) and pull their key from a single
 * pre-configured bean. Validation just needs one raw HTTP call with whatever
 * key was submitted in the request, so a plain RestClient is simpler and
 * keeps this validator fully decoupled from agent execution.
 */
@Component
public class AnthropicProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProviderValidator.class);
    private static final String MODELS_URL = "https://api.anthropic.com/v1/models";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;

    public AnthropicProviderValidator(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.ANTHROPIC;
    }

    @Override
    public void validate(String apiKey) {
        try {
            restClient.get()
                .uri(MODELS_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .retrieve()
                .toBodilessEntity();
            // 2xx => key accepted, nothing further to do.
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new ProviderValidationException(
                    "Anthropic rejected this API key (HTTP " + status.value() + "). "
                        + "Check the key is correct, active, and not revoked.");
            }
            // Non-auth errors (e.g. 429, 5xx) — the key itself may be fine, but we
            // can't confirm it right now. Surface as a validation failure rather
            // than silently marking the provider active.
            log.warn("Anthropic validation call returned unexpected status {}", status.value());
            throw new ProviderValidationException(
                "Could not validate this key against Anthropic right now (HTTP "
                    + status.value() + "). Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("Anthropic validation call failed", e);
            throw new ProviderValidationException(
                "Could not reach Anthropic to validate this key: " + e.getMessage(), e);
        }
    }
}
