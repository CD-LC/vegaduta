package com.agenticai.core.admin.auth0;

import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal subset of Auth0's Management API user object ({@code GET/POST /api/v2/users}) - only the
 * fields {@link Auth0UserAdminClient} reads or writes, in the same spirit as {@link
 * KeycloakUserRepresentation}. Auth0 returns far more (identities, last_ip, logins_count, ...) -
 * ignored on the way in.
 *
 * <p>Field mapping onto the shared port model ({@link KeycloakUserRepresentation}):
 *
 * <ul>
 *   <li>{@code user_id} ({@code "auth0|..."}, {@code "google-oauth2|..."}) -&gt; {@code id}. The
 *       {@code |} makes it URL-unsafe; every use as a path variable is percent-encoded by the HTTP
 *       client.
 *   <li>{@code username} -&gt; {@code username}, falling back to {@code email} when absent (the
 *       field only exists when the connection has {@code requires_username} enabled).
 *   <li>{@code given_name}/{@code family_name} -&gt; {@code firstName}/{@code lastName}.
 *   <li>{@code blocked} -&gt; inverted into {@code enabled} (Auth0 has no {@code enabled} flag).
 *   <li>{@code app_metadata} -&gt; {@code attributes}: scalar values become single-element lists,
 *       matching Keycloak's always-multi-valued attribute shape so port consumers read {@code
 *       tenant_id} identically from either provider.
 *   <li>{@code created_at} (ISO-8601) -&gt; {@code createdTimestamp} epoch millis.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record Auth0UserRepresentation(
        @JsonProperty("user_id") String userId,
        String username,
        String email,
        @JsonProperty("given_name") String givenName,
        @JsonProperty("family_name") String familyName,
        String name,
        Boolean blocked,
        @JsonProperty("email_verified") Boolean emailVerified,
        @JsonProperty("app_metadata") Map<String, Object> appMetadata,
        @JsonProperty("created_at") String createdAt) {

    KeycloakUserRepresentation toPortUser() {
        return new KeycloakUserRepresentation(
                userId,
                username != null && !username.isBlank() ? username : email,
                email,
                givenName,
                familyName,
                blocked == null ? null : !blocked,
                emailVerified,
                List.of(),
                metadataToAttributes(appMetadata),
                List.of(),
                parseCreatedAt(createdAt));
    }

    /**
     * Auth0 {@code app_metadata} is a free-form JSON object with scalar-or-nested values, while the
     * port model's {@code attributes} is Keycloak's {@code Map<String, List<String>>}. Scalars map
     * to single-element lists; a JSON array maps element-wise; nested objects are dropped (the
     * port's callers only ever read flat string attributes - {@code tenant_id}, {@code
     * organization_name}).
     */
    private static Map<String, List<String>> metadataToAttributes(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> attributes = new HashMap<>();
        metadata.forEach(
                (key, value) -> {
                    if (value == null || key == null) {
                        return;
                    }
                    if (value instanceof List<?> list) {
                        attributes.put(
                                key,
                                list.stream()
                                        .filter(java.util.Objects::nonNull)
                                        .map(String::valueOf)
                                        .toList());
                    } else if (!(value instanceof Map)) {
                        attributes.put(key, List.of(String.valueOf(value)));
                    }
                });
        return attributes;
    }

    private static Long parseCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(createdAt).toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
