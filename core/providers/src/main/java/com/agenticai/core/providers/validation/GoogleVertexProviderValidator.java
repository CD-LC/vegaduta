package com.agenticai.core.providers.validation;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.VertexAccessTokenProvider;
import com.agenticai.core.providers.VertexCredential;
import com.agenticai.core.providers.VertexCredentialException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Validates a tenant's own Vertex AI BYOK credential. Unlike every other
 * validator here, {@code apiKey} is not a plain string secret - it is a
 * {@link VertexCredential#encode() encoded} project/location/service-account
 * blob (see that class's javadoc for why the single-column reuse), decoded
 * here, exchanged for a real access token, then checked with a free,
 * non-billed {@code GET .../publishers/google/models} call against the
 * tenant's own project.
 */
@Component
public class GoogleVertexProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(GoogleVertexProviderValidator.class);

    private final VertexAccessTokenProvider tokenProvider;
    private final RestClient restClient;

    public GoogleVertexProviderValidator(VertexAccessTokenProvider tokenProvider, RestClient.Builder restClientBuilder) {
        this.tokenProvider = tokenProvider;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.GOOGLE_VERTEX;
    }

    @Override
    public void validate(String apiKey) {
        VertexCredential credential;
        try {
            credential = VertexCredential.decode(apiKey);
        } catch (VertexCredentialException e) {
            throw new ProviderValidationException(
                "This isn't a valid Vertex AI credential - use the Vertex connection form, "
                    + "which packages your project ID, location, and service-account key together.");
        }

        String token;
        try {
            token = tokenProvider.accessTokenForCredential(credential);
        } catch (VertexCredentialException e) {
            throw new ProviderValidationException("Google rejected this service-account key: " + e.getMessage());
        }

        String url = "https://" + credential.location() + "-aiplatform.googleapis.com/v1/publishers/google/models/gemini-2.5-flash";
        try {
            restClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new ProviderValidationException(
                    "Vertex AI rejected this credential (HTTP " + status.value() + "). "
                        + "Check the project ID, location, and that the Vertex AI API is enabled for this project.");
            }
            log.warn("Vertex AI validation call returned unexpected status {}", status.value());
            throw new ProviderValidationException(
                "Could not validate this credential against Vertex AI right now (HTTP " + status.value() + "). Try again in a moment.");
        } catch (RestClientException e) {
            log.warn("Vertex AI validation call failed", e);
            throw new ProviderValidationException("Could not reach Vertex AI to validate this credential: " + e.getMessage(), e);
        }
    }
}
