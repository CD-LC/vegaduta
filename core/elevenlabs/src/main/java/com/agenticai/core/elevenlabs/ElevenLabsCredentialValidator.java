package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Validates a key with a real, cheap call to {@code GET /v1/user} before
 * {@link ElevenLabsCredentialService#activate} marks it {@code ACTIVE} - same
 * "validate before persist" guarantee {@code SarvamCredentialValidator}
 * gives Sarvam. Unlike Sarvam, ElevenLabs does document a free metadata
 * endpoint ({@code /v1/user} - returns subscription/usage info, no side
 * effects, no cost), so this is simpler than Sarvam's validator: an ordinary
 * GET rather than a synthetic paid request.
 *
 * <p>Auth uses the {@code xi-api-key} header - ElevenLabs' documented auth
 * scheme, distinct from Sarvam's {@code api-subscription-key} or Twilio's
 * basic auth.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class ElevenLabsCredentialValidator {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsCredentialValidator.class);
    private static final String USER_URL = "https://api.elevenlabs.io/v1/user";
    private static final String API_KEY_HEADER = "xi-api-key";

    private final RestClient restClient;

    public ElevenLabsCredentialValidator(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public void validate(String apiKey) {
        try {
            restClient.get()
                .uri(USER_URL)
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String body = e.getResponseBodyAsString();
            String detail = (body == null || body.isBlank()) ? null : body.trim();

            if (status.value() == 401) {
                throw new ElevenLabsCredentialValidationException(
                    "ElevenLabs rejected this API key (HTTP 401)"
                        + (detail != null ? ": " + detail : ".")
                        + " Check the key is correct, active, and not revoked.");
            }
            log.warn("ElevenLabs validation call returned unexpected status {}", status.value());
            throw new ElevenLabsCredentialValidationException(
                "Could not validate this key against ElevenLabs right now (HTTP " + status.value() + ")"
                    + (detail != null ? ": " + detail : ".") + " Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("ElevenLabs validation call failed", e);
            throw new ElevenLabsCredentialValidationException(
                "Could not reach ElevenLabs to validate this key: " + e.getMessage(), e);
        }
    }
}
