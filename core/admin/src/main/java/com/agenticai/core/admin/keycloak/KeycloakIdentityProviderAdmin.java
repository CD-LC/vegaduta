package com.agenticai.core.admin.keycloak;

import com.agenticai.core.admin.identity.IdentityProviderAdminPort;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Identity-provider (SSO and social login) half of the Keycloak Admin API adapter. Implements
 * {@link IdentityProviderAdminPort}; {@link KeycloakAdminClient} extends it to expose both ports as
 * the single Spring bean.
 */
abstract class KeycloakIdentityProviderAdmin extends KeycloakAdminSupport
        implements IdentityProviderAdminPort {

    KeycloakIdentityProviderAdmin(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String realm,
            String clientId,
            String clientSecret) {
        super(restClientBuilder, baseUrl, realm, clientId, clientSecret);
    }

    /**
     * {@code GET /api/auth/social-providers}'s data source ({@link
     * com.agenticai.core.admin.SocialAuthConfigController}) - the realm's actually-configured
     * identity brokers (Google/GitHub/Microsoft), reflecting whatever {@code
     * deploy/enable-social-idps.sh}/ {@code reconcile-keycloak.sh} last set at runtime, not just
     * what {@code agentic-ai-realm.json} ships with at import time (those two scripts PATCH the
     * live realm after import - the static file is only the starting point, so parsing it would
     * drift the moment an operator toggles a provider by hand or via those scripts).
     *
     * <p><b>Requires one additional one-time Keycloak admin-console grant</b> beyond the {@code
     * manage-users}/{@code view-users} roles this class's class-level javadoc already documents:
     * the {@code agentic-ai-core} client's service account also needs the {@code realm-management}
     * client's {@code view-identity-providers} role, or this 403s exactly like an unfixed
     * manage-users/view-users gap would. Not encoded in {@code agentic-ai-realm.json} for the same
     * reason those two aren't either - client-scope role grants for a client's own service account
     * aren't part of a realm export's client/role definitions, they're a separate
     * service-account-role-mapping step done once per environment.
     */
    @Override
    public List<KeycloakIdentityProviderRepresentation> listIdentityProviders() {
        String token = obtainAdminToken();
        List<KeycloakIdentityProviderRepresentation> providers =
                call(
                        () ->
                                restClient
                                        .get()
                                        .uri(
                                                "/admin/realms/{realm}/identity-provider/instances",
                                                realm)
                                        .headers(authHeaders(token))
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        List<
                                                                KeycloakIdentityProviderRepresentation>>() {}),
                        "list identity providers");
        return providers == null ? List.of() : providers;
    }

    /**
     * Per-tenant BYO-IdP (P2 SSO productization): fetches one IdP instance's FULL representation -
     * including the {@code config} map {@link KeycloakIdentityProviderRepresentation} deliberately
     * omits - so {@code TenantSsoService} can merge updates without clobbering fields it doesn't
     * manage (e.g. Keycloak-set defaults, mappers). Returns empty when the alias doesn't exist (404
     * is expected, not an exception).
     */
    @SuppressWarnings("unchecked")
    @Override
    public java.util.Optional<Map<String, Object>> getIdentityProvider(String alias) {
        String token = obtainAdminToken();
        try {
            Map<String, Object> body =
                    restClient
                            .get()
                            .uri(
                                    "/admin/realms/{realm}/identity-provider/instances/{alias}",
                                    realm,
                                    alias)
                            .headers(authHeaders(token))
                            .retrieve()
                            .body(Map.class);
            return java.util.Optional.ofNullable(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return java.util.Optional.empty();
            }
            throw new KeycloakAdminException(
                    "Keycloak admin API call failed (get identity provider): HTTP "
                            + e.getStatusCode().value()
                            + " - "
                            + e.getResponseBodyAsString(),
                    e);
        }
    }

    /**
     * Creates a realm-level identity provider instance. {@code payload} is the full Keycloak {@code
     * IdentityProviderRepresentation} JSON shape ({@code alias}, {@code providerId}, {@code
     * enabled}, {@code config} map, trust flags) - the caller ({@code TenantSsoService}) owns
     * building it so this class stays a thin, provider-agnostic admin client. A 409 on an existing
     * alias surfaces as {@link KeycloakAdminException} - the service layer upserts via {@link
     * #getIdentityProvider} + {@link #updateIdentityProvider} instead of catching that.
     */
    @Override
    public void createIdentityProvider(Map<String, Object> payload) {
        String token = obtainAdminToken();
        call(
                () ->
                        restClient
                                .post()
                                .uri("/admin/realms/{realm}/identity-provider/instances", realm)
                                .headers(authHeaders(token))
                                .body(payload)
                                .retrieve()
                                .toBodilessEntity(),
                "create identity provider");
    }

    /**
     * Full-representation PUT - Keycloak's update endpoint replaces the whole instance, so callers
     * MUST merge their changes into the map {@link #getIdentityProvider} returned rather than send
     * a partial document (a partial PUT silently drops every field it doesn't carry, including the
     * config map's client secret).
     */
    @Override
    public void updateIdentityProvider(String alias, Map<String, Object> fullRepresentation) {
        String token = obtainAdminToken();
        call(
                () ->
                        restClient
                                .put()
                                .uri(
                                        "/admin/realms/{realm}/identity-provider/instances/{alias}",
                                        realm,
                                        alias)
                                .headers(authHeaders(token))
                                .body(fullRepresentation)
                                .retrieve()
                                .toBodilessEntity(),
                "update identity provider");
    }

    @Override
    public void deleteIdentityProvider(String alias) {
        String token = obtainAdminToken();
        call(
                () ->
                        restClient
                                .delete()
                                .uri(
                                        "/admin/realms/{realm}/identity-provider/instances/{alias}",
                                        realm,
                                        alias)
                                .headers(authHeaders(token))
                                .retrieve()
                                .toBodilessEntity(),
                "delete identity provider");
    }

    /**
     * SEC-019: the mappers attached to one IdP instance (full representations, including {@code
     * id}).
     */
    @Override
    public List<Map<String, Object>> listIdentityProviderMappers(String alias) {
        String token = obtainAdminToken();
        List<Map<String, Object>> mappers =
                call(
                        () ->
                                restClient
                                        .get()
                                        .uri(
                                                "/admin/realms/{realm}/identity-provider/instances/{alias}/mappers",
                                                realm,
                                                alias)
                                        .headers(authHeaders(token))
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        List<Map<String, Object>>>() {}),
                        "list identity provider mappers");
        return mappers == null ? List.of() : mappers;
    }

    /** SEC-019: create an IdP mapper ({@code IdentityProviderMapperRepresentation}). */
    @Override
    public void createIdentityProviderMapper(String alias, Map<String, Object> mapper) {
        String token = obtainAdminToken();
        call(
                () ->
                        restClient
                                .post()
                                .uri(
                                        "/admin/realms/{realm}/identity-provider/instances/{alias}/mappers",
                                        realm,
                                        alias)
                                .headers(authHeaders(token))
                                .body(mapper)
                                .retrieve()
                                .toBodilessEntity(),
                "create identity provider mapper");
    }

    /** SEC-019: replace an existing IdP mapper by id. */
    @Override
    public void updateIdentityProviderMapper(
            String alias, String mapperId, Map<String, Object> mapper) {
        String token = obtainAdminToken();
        call(
                () ->
                        restClient
                                .put()
                                .uri(
                                        "/admin/realms/{realm}/identity-provider/instances/{alias}/mappers/{id}",
                                        realm,
                                        alias,
                                        mapperId)
                                .headers(authHeaders(token))
                                .body(mapper)
                                .retrieve()
                                .toBodilessEntity(),
                "update identity provider mapper");
    }
}
