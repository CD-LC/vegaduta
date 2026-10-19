package com.agenticai.core.admin.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal subset of Keycloak's {@code IdentityProviderRepresentation} - only
 * {@code alias} and {@code enabled}, the two fields
 * {@link com.agenticai.core.admin.SocialAuthConfigController} needs to answer
 * "which social brokers are actually turned on right now." Same
 * {@code ignoreUnknown} posture as {@link KeycloakUserRepresentation}: the
 * real representation carries ~15 more fields (config map with client
 * secrets, mapper settings, etc.) this codebase has no reason to ever
 * deserialize.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakIdentityProviderRepresentation(
    String alias,
    Boolean enabled
) {
}
