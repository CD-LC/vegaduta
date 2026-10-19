package com.agenticai.core.admin.identity;

import com.agenticai.core.admin.keycloak.KeycloakIdentityProviderRepresentation;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Administration of federated identity providers (per-tenant SSO, social login) and their claim
 * mappers on the identity provider.
 *
 * <p>Separate from {@link IdentityUserAdminPort} because the callers are disjoint (SSO and social
 * login configuration versus user management) and an adapter may support one without the other. The
 * payload maps are the provider's own representation (OIDC/SAML settings); they are passed through,
 * not interpreted, by the platform.
 */
public interface IdentityProviderAdminPort {

    List<KeycloakIdentityProviderRepresentation> listIdentityProviders();

    Optional<Map<String, Object>> getIdentityProvider(String alias);

    void createIdentityProvider(Map<String, Object> payload);

    void updateIdentityProvider(String alias, Map<String, Object> fullRepresentation);

    void deleteIdentityProvider(String alias);

    List<Map<String, Object>> listIdentityProviderMappers(String alias);

    void createIdentityProviderMapper(String alias, Map<String, Object> mapper);

    void updateIdentityProviderMapper(String alias, String mapperId, Map<String, Object> mapper);
}
