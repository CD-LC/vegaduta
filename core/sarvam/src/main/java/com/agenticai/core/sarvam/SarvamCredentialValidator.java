package com.agenticai.core.sarvam;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Validates a key with a real (but
 * minimal) call to {@code POST /v1/chat/completions} before {@link
 * SarvamCredentialService#activate} marks it {@code ACTIVE}. Sarvam has no
 * documented free/metadata endpoint (no {@code GET /v1/models}) the way
 * Anthropic/OpenRouter/Grok do, so this uses the cheapest possible real
 * request instead: {@code sarvam-105b}, a one-word user message, {@code
 * max_tokens=1}.
 *
 * <p>Deliberately NOT {@code core.providers.validation.ProviderValidator}:
 * that interface is Spring-collected wholesale into {@code
 * core.providers.ProviderService}'s generic {@code /api/providers} activation
 * path, which persists into {@code LlmProviderCredential} - a second,
 * disconnected credential store for Sarvam that {@link
 * com.agenticai.core.providers.ProviderCredentialResolver#resolveActiveKey}
 * would never actually read (it always branches {@code SARVAM} to {@link
 * SarvamCredentialService} - see that method's javadoc). Wiring this class
 * into that interface would let a tenant "successfully" activate Sarvam via
 * the generic Providers screen while the credential silently never gets
 * used. Called directly by {@link SarvamCredentialService#activate} instead,
 * exactly like {@code TwilioCredentialService.validate} calls its own
 * dedicated validator rather than going through {@code ProviderValidator}.
 *
 * <p>Auth uses the {@code api-subscription-key} header, matching {@code
 * core.chat.SarvamAgentChatClientFactory}'s auth choice.
 *
 * <p><b>The documented gotcha this class exists to handle correctly:</b>
 * Sarvam's own API reference lists {@code 403 Forbidden Error} as a distinct
 * error type from {@code 400 Bad Request Error} - auth failures return HTTP
 * 403, not 401, the opposite of most providers in this codebase. Both 401
 * and 403 are treated as key rejection here (Sarvam could plausibly return
 * either for a bad key), and since the request body is fixed and known-valid,
 * a 400 here is also far more likely to indicate a real account/key problem
 * than a malformed request.
 */
@Component
public class SarvamCredentialValidator {

    private static final Logger log = LoggerFactory.getLogger(SarvamCredentialValidator.class);
    private static final String CHAT_COMPLETIONS_URL = "https://api.sarvam.ai/v1/chat/completions";
    private static final String API_SUBSCRIPTION_KEY_HEADER = "api-subscription-key";
    private static final String VALIDATION_MODEL = "sarvam-105b";

    private final RestClient restClient;

    public SarvamCredentialValidator(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public void validate(String apiKey) {
        try {
            restClient.post()
                .uri(CHAT_COMPLETIONS_URL)
                .header(API_SUBSCRIPTION_KEY_HEADER, apiKey)
                .body(Map.of(
                    "model", VALIDATION_MODEL,
                    "messages", List.of(Map.of("role", "user", "content", "Hi")),
                    "max_tokens", 1
                ))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String body = e.getResponseBodyAsString();
            String detail = (body == null || body.isBlank()) ? null : body.trim();

            if (status.value() == 401 || status.value() == 403) {
                throw new SarvamCredentialValidationException(
                    "Sarvam rejected this API key (HTTP " + status.value() + ")"
                        + (detail != null ? ": " + detail : ".")
                        + " Check the key is correct, active, and not revoked.");
            }
            log.warn("Sarvam validation call returned unexpected status {}", status.value());
            throw new SarvamCredentialValidationException(
                "Could not validate this key against Sarvam right now (HTTP " + status.value() + ")"
                    + (detail != null ? ": " + detail : ".") + " Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("Sarvam validation call failed", e);
            throw new SarvamCredentialValidationException(
                "Could not reach Sarvam to validate this key: " + e.getMessage(), e);
        }
    }
}
