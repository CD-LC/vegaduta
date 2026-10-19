package com.agenticai.core.admin.auth0;

import com.agenticai.core.admin.keycloak.KeycloakAdminException;
import com.agenticai.core.admin.keycloak.KeycloakUserConflictException;
import com.agenticai.core.admin.keycloak.KeycloakUserValidationException;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP plumbing shared by the Auth0 Management API adapter: the cached client_credentials token,
 * bearer headers, upstream-failure translation (generic and create-user specific), paged user and
 * role reads, and Lucene escaping. Package-private, the Auth0 counterpart of the Keycloak
 * adapter's {@code KeycloakAdminSupport}; the public surface is {@link Auth0UserAdminClient}.
 */
abstract class Auth0ManagementSupport {

    private static final ObjectMapper OBJECT_MAPPER = JsonMappers.plain();

    /**
     * {@code per_page} ceiling of {@code GET /api/v2/users} (Auth0's documented maximum).
     */
    static final int USER_PAGE_SIZE = 100;

    /**
     * Hard ceiling on user-list pages. Auth0's search endpoint returns at most 1000 users for a
     * query (its documented search limit), so ten full pages is every user Auth0 will ever hand
     * out for one call anyway; the ceiling also guarantees termination against a misbehaving
     * server, same as the Keycloak adapter's {@code MAX_USER_PAGES}.
     */
    static final int MAX_USER_PAGES = 10;

    /** Refresh the cached Management API token this far before {@code expires_in}. */
    private static final long TOKEN_EXPIRY_SKEW_SECONDS = 60;

    /**
     * Operator-facing hint appended to generic upstream-failure messages - same role as {@code
     * KeycloakAdminSupport.OPS_HINT} (stripped from HTTP responses by {@code
     * GlobalExceptionHandler}, kept in logs).
     */
    static final String OPS_HINT =
            ". If this is 403, confirm the Auth0 application's Management API scopes "
                    + "(read:users, create:users, update:users, delete:users, read:roles, "
                    + "delete:sessions, delete:refresh_tokens) and that the application has the "
                    + "database connection enabled for the reset-email endpoints.";

    final RestClient restClient;
    final String clientId;
    private final String clientSecret;
    final String connection;
    private final String audience;

    private String cachedToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    /**
     * @param restClientBuilder the AUTO-CONFIGURED builder - injected, never a bare {@code
     *     RestClient.builder()}, so {@code spring.http.client.*} timeouts apply (same fix as
     *     {@link com.agenticai.core.admin.keycloak.KeycloakAdminClient}'s constructor javadoc).
     * @param domain the Auth0 tenant domain; a bare host ({@code my-tenant.auth0.com}) or a full
     *     URL ({@code https://my-tenant.auth0.com}) - normalized in the constructor.
     */
    Auth0ManagementSupport(
            RestClient.Builder restClientBuilder,
            String domain,
            String clientId,
            String clientSecret,
            String connection,
            String audience) {
        String baseUrl = normalizeDomain(domain);
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.connection = connection;
        this.audience =
                audience == null || audience.isBlank() ? baseUrl + "/api/v2/" : audience;
    }

    static String normalizeDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        return normalized;
    }

    /**
     * Management API token via client_credentials on {@code POST /oauth/token}, cached until 60s
     * before {@code expires_in}. Synchronized: admin-panel traffic is low-volume, so a monitor is
     * plenty and keeps concurrent first-use from minting two tokens.
     */
    synchronized String obtainManagementToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        Map<String, Object> response =
                call(
                        () ->
                                restClient
                                        .post()
                                        .uri("/oauth/token")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(
                                                Map.of(
                                                        "grant_type", "client_credentials",
                                                        "client_id", clientId,
                                                        "client_secret", clientSecret,
                                                        "audience", audience))
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        Map<String, Object>>() {}),
                        "obtain management token");
        if (response == null || response.get("access_token") == null) {
            throw new KeycloakAdminException("Auth0 token endpoint returned no access_token");
        }
        Object expiresIn = response.get("expires_in");
        long ttlSeconds = expiresIn instanceof Number number ? number.longValue() : 86_400L;
        cachedToken = String.valueOf(response.get("access_token"));
        tokenExpiresAt =
                Instant.now()
                        .plusSeconds(Math.max(0L, ttlSeconds - TOKEN_EXPIRY_SKEW_SECONDS));
        return cachedToken;
    }

    final Consumer<HttpHeaders> authHeaders(String token) {
        return headers -> headers.setBearerAuth(token);
    }

    final <T> T call(Supplier<T> action, String what) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            throw new KeycloakAdminException(
                    "Auth0 Management API call failed ("
                            + what
                            + "): HTTP "
                            + e.getStatusCode().value()
                            + " - "
                            + e.getResponseBodyAsString()
                            + OPS_HINT,
                    e);
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Could not reach Auth0 (" + what + "): " + e.getMessage(), e);
        }
    }

    /**
     * {@code GET /api/v2/users} pages into the port model. {@code luceneQuery} is a user-search v3
     * query ({@code q} param) or {@code null} for an unfiltered listing; either way Auth0 stops
     * answering after 1000 hits, so {@link #MAX_USER_PAGES} is a real bound, not just a safety
     * stop.
     */
    final List<Auth0UserRepresentation> pagedUsers(String token, String luceneQuery) {
        List<Auth0UserRepresentation> all = new ArrayList<>();
        for (int page = 0; page < MAX_USER_PAGES; page++) {
            final int currentPage = page;
            List<Auth0UserRepresentation> batch =
                    call(
                            () ->
                                    restClient
                                            .get()
                                            .uri(
                                                    luceneQuery == null
                                                            ? "/api/v2/users?page={page}&per_page={size}"
                                                            : "/api/v2/users?q={q}&search_engine=v3&page={page}&per_page={size}",
                                                    luceneQuery == null
                                                            ? new Object[] {
                                                                currentPage, USER_PAGE_SIZE
                                                            }
                                                            : new Object[] {
                                                                luceneQuery,
                                                                currentPage,
                                                                USER_PAGE_SIZE
                                                            })
                                            .headers(authHeaders(token))
                                            .retrieve()
                                            .body(
                                                    new ParameterizedTypeReference<
                                                            List<Auth0UserRepresentation>>() {}),
                            "list users");
            if (batch == null || batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < USER_PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    final Auth0UserRepresentation fetchUser(String userId, String token) {
        // userId is "auth0|..."/"google-oauth2|..." - the | is percent-encoded by the URI
        // template expansion ({id} values are encoded for path position).
        try {
            Auth0UserRepresentation user =
                    restClient
                            .get()
                            .uri("/api/v2/users/{id}", userId)
                            .headers(authHeaders(token))
                            .retrieve()
                            .body(Auth0UserRepresentation.class);
            if (user == null) {
                throw new KeycloakAdminException("No user found with id " + userId);
            }
            return user;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new KeycloakAdminException("No user found with id " + userId, e);
            }
            throw new KeycloakAdminException(
                    "Auth0 Management API call failed (get user by id): HTTP "
                            + e.getStatusCode().value()
                            + " - "
                            + e.getResponseBodyAsString()
                            + OPS_HINT,
                    e);
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Could not reach Auth0 (get user by id): " + e.getMessage(), e);
        }
    }

    final List<Auth0RoleRepresentation> userRoles(String userId, String token) {
        List<Auth0RoleRepresentation> roles =
                call(
                        () ->
                                restClient
                                        .get()
                                        .uri(
                                                "/api/v2/users/{id}/roles?per_page={size}",
                                                userId,
                                                USER_PAGE_SIZE)
                                        .headers(authHeaders(token))
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        List<Auth0RoleRepresentation>>() {}),
                        "get user roles");
        return roles == null ? List.of() : roles;
    }

    /** {@code GET /api/v2/roles} paged - every role defined in the Auth0 tenant, name -&gt; id. */
    final Map<String, String> roleIdsByName(String token) {
        Map<String, String> idsByName = new HashMap<>();
        for (int page = 0; page < MAX_USER_PAGES; page++) {
            final int currentPage = page;
            List<Auth0RoleRepresentation> batch =
                    call(
                            () ->
                                    restClient
                                            .get()
                                            .uri(
                                                    "/api/v2/roles?page={page}&per_page={size}",
                                                    currentPage,
                                                    USER_PAGE_SIZE)
                                            .headers(authHeaders(token))
                                            .retrieve()
                                            .body(
                                                    new ParameterizedTypeReference<
                                                            List<Auth0RoleRepresentation>>() {}),
                            "list roles");
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (Auth0RoleRepresentation role : batch) {
                idsByName.put(role.name(), role.id());
            }
            if (batch.size() < USER_PAGE_SIZE) {
                break;
            }
        }
        return idsByName;
    }

    final List<String> resolveRoleIds(
            Set<String> roleNames, Map<String, String> roleIdsByName) {
        List<String> ids = new ArrayList<>(roleNames.size());
        for (String roleName : roleNames) {
            String id = roleIdsByName.get(roleName);
            if (id == null) {
                throw new KeycloakAdminException(
                        "Auth0 role '" + roleName + "' does not exist");
            }
            ids.add(id);
        }
        return ids;
    }

    /**
     * {@code createUser}'s POST-only variant of {@link #call}: Auth0's two routine,
     * admin-actionable rejections are translated into the port's typed exceptions rather than the
     * generic 502 - a 409 {@code {"statusCode":409,"error":"Conflict","message":"The user already
     * exists."}} becomes {@link KeycloakUserConflictException}, a 400 carrying Auth0's {@code
     * message} (e.g. password-policy or connection rejections) becomes {@link
     * KeycloakUserValidationException} with that message surfaced.
     */
    final <T> T callCreateUser(Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            throw translateCreateUserRejection(e);
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Could not reach Auth0 (create user): " + e.getMessage(), e);
        }
    }

    private KeycloakAdminException translateCreateUserRejection(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        if (status == 409) {
            String message = extractAuth0Message(body);
            return new KeycloakUserConflictException(
                    message != null
                            ? message
                            : "A user with these details already exists.",
                    e);
        }
        if (status == 400) {
            String message = extractAuth0Message(body);
            return new KeycloakUserValidationException(
                    message != null
                            ? message
                            : "The user details were rejected - check username and email, then"
                                    + " try again.",
                    e);
        }
        return new KeycloakAdminException(
                "Auth0 Management API call failed (create user): HTTP "
                        + status
                        + " - "
                        + body
                        + OPS_HINT,
                e);
    }

    /**
     * Auth0's error bodies carry the human-readable reason in {@code "message"} ({@code
     * {"statusCode":409,"error":"Conflict","message":"The user already exists."}}). Returns null
     * on any unparseable/other shape - never throws.
     */
    private static String extractAuth0Message(String body) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            String message = node.path("message").asText(null);
            return message == null || message.isBlank() ? null : message;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Minimal Lucene-term escaping for user-supplied values embedded in a quoted {@code q}
     * string (tenant ids and emails - neither legitimately contains {@code "} or {@code \}, but
     * a crafted value must not break the query syntax).
     */
    static String escapeLucene(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
