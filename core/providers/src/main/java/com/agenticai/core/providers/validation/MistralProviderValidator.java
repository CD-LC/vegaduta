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
 * Validates a Mistral AI API key with a call to {@code GET /v1/models} -
 * Mistral's API is OpenAI-chat-completions-compatible (see {@code
 * MistralAgentChatClientFactory}), and {@code /v1/models} is the same free,
 * non-billed metadata endpoint {@link GrokProviderValidator} and {@link
 * OpenRouterProviderValidator} already use for this exact check.
 */
@Component
public class MistralProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(MistralProviderValidator.class);
    private static final String MODELS_URL = "https://api.mistral.ai/v1/models";

    private final RestClient restClient;

    public MistralProviderValidator(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.MISTRAL;
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
            if (status.value() == 401 || status.value() == 403) {
                throw new ProviderValidationException(
                    "Mistral AI rejected this API key (HTTP " + status.value() + ")"
                        + (detail != null ? ": " + detail : ".") + " Check the key at console.mistral.ai.");
            }
            log.warn("Mistral validation call returned unexpected status {}", status.value());
            throw new ProviderValidationException(
                "Could not validate this key against Mistral AI right now (HTTP " + status.value() + ")"
                    + (detail != null ? ": " + detail : ".") + " Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("Mistral validation call failed", e);
            throw new ProviderValidationException(
                "Could not reach Mistral AI to validate this key: " + e.getMessage(), e);
        }
    }
}
