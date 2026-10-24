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
 * Validates a real OpenAI API key with a call to GET /v1/models - the same
 * free, non-billed metadata endpoint {@link GrokProviderValidator}/{@code
 * OpenRouterProviderValidator} already use for this exact check, since
 * OpenAI's API is what those two providers' APIs are compatible WITH in the
 * first place.
 *
 * <p>OpenAI's own error-code reference (platform.openai.com/docs/guides/error-codes)
 * documents 401 for "invalid authentication" (bad key format, revoked key)
 * and 429 specifically for "you have hit your assigned rate limit" as well
 * as, less commonly, "you have run out of credits" - unlike Grok, OpenAI does
 * distinguish a truly invalid key (401) from an out-of-quota one (429), so
 * both are surfaced here as separate, accurately-worded messages rather than
 * folded into one generic "key rejected" case.
 */
@Component
public class OpenAIProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProviderValidator.class);
    private final String modelsUrl;

    private final RestClient restClient;

    /** Vendor defaults; OpenAI is called at the address {@code app.llm.endpoints} names. */
    public OpenAIProviderValidator(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, new ProviderEndpoints());
    }

    @Autowired
    public OpenAIProviderValidator(RestClient.Builder restClientBuilder, ProviderEndpoints endpoints) {
        this.restClient = restClientBuilder.build();
        this.modelsUrl = endpoints.modelsUrl(ProviderName.OPENAI);
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.OPENAI;
    }

    @Override
    public void validate(String apiKey) {
        try {
            restClient.get()
                .uri(modelsUrl)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String body = e.getResponseBodyAsString();
            String detail = (body == null || body.isBlank()) ? null : body.trim();

            if (status.value() == 401 || status.value() == 403) {
                throw new ProviderValidationException(
                    "OpenAI rejected this API key (HTTP " + status.value() + ")"
                        + (detail != null ? ": " + detail : ".")
                        + " Check that the key is correct and hasn't been revoked.");
            }
            if (status.value() == 429) {
                throw new ProviderValidationException(
                    "OpenAI accepted this key's identity but rejected the request (HTTP 429)"
                        + (detail != null ? ": " + detail : ".")
                        + " This usually means the account behind this key has no billing method / "
                        + "credit balance, or has hit its rate limit - check "
                        + "https://platform.openai.com/account/billing.");
            }
            log.warn("OpenAI validation call returned unexpected status {}", status.value());
            throw new ProviderValidationException(
                "Could not validate this key against OpenAI right now (HTTP " + status.value() + ")"
                    + (detail != null ? ": " + detail : ".") + " Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("OpenAI validation call failed", e);
            throw new ProviderValidationException(
                "Could not reach OpenAI to validate this key: " + e.getMessage(), e);
        }
    }
}
