package com.agenticai.core.agents.decision;

import com.agenticai.core.providers.capability.Capability;
import com.agenticai.core.providers.capability.ProviderCredentialValidator;
import com.agenticai.core.providers.capability.ProviderKeys;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Validates a candidate Jev key with a real (but minimal) call to {@code POST
 * /v1/systemone} before {@code ProviderCredentialService#activate} marks the
 * {@code TYPESAFE} row {@code ACTIVE} - same validate-before-persist guarantee
 * {@code SarvamCredentialValidator} gives that credential path. TypeSafe has
 * no documented metadata endpoint to probe, so this uses the cheapest possible
 * real request: the configured model, a trivial state, one {@code noul}
 * question. Cost of a validation call is a handful of input tokens.
 *
 * <p>Hits {@code app.jev.base-url}, not a hardcoded host, so a deployment
 * fronting TypeSafe through a compatible gateway validates against the same
 * endpoint its real calls use. Auth is the same {@code Authorization: Bearer}
 * header {@link JevDecisionService} sends.
 *
 * <p>Implements the registry's {@link ProviderCredentialValidator} SPI: it is
 * what makes {@code provider_key=TYPESAFE} activatable through the generic
 * {@code /api/capabilities/decision/credential} endpoint (and what stamps
 * {@code DECISION} onto the row's {@code capabilities} array).
 */
@Component
public class JevCredentialValidator implements ProviderCredentialValidator {

    private static final Logger log = LoggerFactory.getLogger(JevCredentialValidator.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final String model;

    public JevCredentialValidator(
        RestClient.Builder restClientBuilder,
        @Value("${app.jev.base-url:" + JevDecisionService.DEFAULT_BASE_URL + "}") String baseUrl,
        @Value("${app.jev.model:" + JevDecisionService.DEFAULT_MODEL + "}") String model
    ) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String providerKey() {
        return ProviderKeys.TYPESAFE;
    }

    @Override
    public String displayName() {
        return "TypeSafe Jev";
    }

    @Override
    public List<String> capabilities() {
        return List.of(Capability.DECISION.name());
    }

    @Override
    public void validate(String apiKey) {
        try {
            restClient.post()
                .uri(baseUrl + "/v1/systemone")
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of(
                    "model", model,
                    "state", "The sky is blue.",
                    "questions", Map.of("ping", Map.of(
                        "type", "noul",
                        "instructions", "Is the sky blue?"))
                ))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String body = e.getResponseBodyAsString();
            String detail = (body == null || body.isBlank()) ? null : body.trim();

            if (status.value() == 401 || status.value() == 403) {
                throw new JevCredentialValidationException(
                    "TypeSafe rejected this API key (HTTP " + status.value() + ")"
                        + (detail != null ? ": " + detail : ".")
                        + " Check the key is correct, active, and not revoked.");
            }
            log.warn("Jev validation call returned unexpected status {}", status.value());
            throw new JevCredentialValidationException(
                "Could not validate this key against TypeSafe right now (HTTP " + status.value() + ")"
                    + (detail != null ? ": " + detail : ".") + " Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("Jev validation call failed", e);
            throw new JevCredentialValidationException(
                "Could not reach TypeSafe to validate this key: " + e.getMessage(), e);
        }
    }
}
