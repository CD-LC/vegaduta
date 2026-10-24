package com.agenticai.core.providers.validation;

import com.agenticai.core.providers.ProviderEndpoints;
import com.agenticai.core.providers.ProviderName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Validates an OpenRouter API key with a call to GET /api/v1/auth/key -
 * OpenRouter's own purpose-built "tell me about the key I just sent you"
 * endpoint (returns label, usage, and rate-limit info), so this both
 * confirms the key is valid AND costs nothing against the tenant's model
 * usage - same "metadata call, not a generation call" property {@link
 * AnthropicProviderValidator} already established for this codebase's other
 * validators.
 */
@Component
public class OpenRouterProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterProviderValidator.class);
    private final String authKeyUrl;

    private final RestClient restClient;

    /** Vendor defaults; OpenRouter is called at the address {@code app.llm.endpoints} names. */
    public OpenRouterProviderValidator(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, new ProviderEndpoints());
    }

    @Autowired
    public OpenRouterProviderValidator(RestClient.Builder restClientBuilder, ProviderEndpoints endpoints) {
        this.restClient = restClientBuilder.build();
        this.authKeyUrl = endpoints.openrouterAuthKeyUrl();
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.OPENROUTER;
    }

    @Override
    public void validate(String apiKey) {
        try {
            restClient.get()
                .uri(authKeyUrl)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new ProviderValidationException(
                    "OpenRouter rejected this API key (HTTP " + status.value() + "). "
                        + "Check the key is correct, active, and not revoked.");
            }
            log.warn("OpenRouter validation call returned unexpected status {}", status.value());
            throw new ProviderValidationException(
                "Could not validate this key against OpenRouter right now (HTTP "
                    + status.value() + "). Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("OpenRouter validation call failed", e);
            throw new ProviderValidationException(
                "Could not reach OpenRouter to validate this key: " + e.getMessage(), e);
        }
    }
}
