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
 * Validates a Google AI Studio (Gemini Developer API) key with a call to
 * GET /v1beta/models - a free, non-billed metadata listing. Uses the
 * {@code x-goog-api-key} header rather than Google's alternative
 * {@code ?key=} query-string form specifically to avoid ever putting the raw
 * key in a URL (server access logs, proxies, etc. see the URL, not just
 * headers) - matches this platform's privacy rule against putting sensitive
 * data in query strings.
 */
@Component
public class GeminiProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(GeminiProviderValidator.class);
    private final String modelsUrl;

    private final RestClient restClient;

    /** Vendor defaults; Gemini is called at the address {@code app.llm.endpoints} names. */
    public GeminiProviderValidator(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, new ProviderEndpoints());
    }

    @Autowired
    public GeminiProviderValidator(RestClient.Builder restClientBuilder, ProviderEndpoints endpoints) {
        this.restClient = restClientBuilder.build();
        this.modelsUrl = endpoints.modelsUrl(ProviderName.GEMINI);
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.GEMINI;
    }

    @Override
    public void validate(String apiKey) {
        try {
            restClient.get()
                .uri(modelsUrl)
                .header("x-goog-api-key", apiKey)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new ProviderValidationException(
                    "Google rejected this API key (HTTP " + status.value() + "). "
                        + "Check the key is correct, active, and not revoked.");
            }
            log.warn("Gemini validation call returned unexpected status {}", status.value());
            throw new ProviderValidationException(
                "Could not validate this key against Google's Gemini API right now (HTTP "
                    + status.value() + "). Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("Gemini validation call failed", e);
            throw new ProviderValidationException(
                "Could not reach Google's Gemini API to validate this key: " + e.getMessage(), e);
        }
    }
}
