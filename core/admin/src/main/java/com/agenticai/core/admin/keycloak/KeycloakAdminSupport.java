package com.agenticai.core.admin.keycloak;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP plumbing shared by the Keycloak Admin API adapter: the {@code client_credentials} admin
 * token, bearer headers and the upstream-failure translation. Package-private; the public surface
 * is {@link KeycloakAdminClient} behind the identity ports.
 */
abstract class KeycloakAdminSupport {

    /**
     * Operator-facing hint appended to generic upstream-failure messages. Written for LOGS and the
     * Issues portal - {@code GlobalExceptionHandler} strips everything from this marker onward
     * before putting the message in a response body, since a tenant admin can do nothing with it.
     */
    static final String OPS_HINT =
            ". If this is 403, confirm the agentic-ai-core service account has the "
                    + "realm-management client's manage-users/view-users roles assigned.";

    final RestClient restClient;
    final String realm;
    private final String clientId;
    private final String clientSecret;

    KeycloakAdminSupport(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String realm,
            String clientId,
            String clientSecret) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    final String obtainAdminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<String, Object> response =
                call(
                        () ->
                                restClient
                                        .post()
                                        .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                        .body(form)
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        Map<String, Object>>() {}),
                        "obtain admin token");
        if (response == null || response.get("access_token") == null) {
            throw new KeycloakAdminException("Keycloak token endpoint returned no access_token");
        }
        return String.valueOf(response.get("access_token"));
    }

    final java.util.function.Consumer<HttpHeaders> authHeaders(String token) {
        return headers -> headers.setBearerAuth(token);
    }

    final <T> T call(java.util.function.Supplier<T> action, String what) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            throw new KeycloakAdminException(
                    "Keycloak admin API call failed ("
                            + what
                            + "): HTTP "
                            + e.getStatusCode().value()
                            + " - "
                            + e.getResponseBodyAsString()
                            + OPS_HINT,
                    e);
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Could not reach Keycloak (" + what + "): " + e.getMessage(), e);
        }
    }
}
