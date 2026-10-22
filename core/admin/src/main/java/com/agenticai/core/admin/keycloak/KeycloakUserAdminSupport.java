package com.agenticai.core.admin.keycloak;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * User-write plumbing of the Keycloak Admin API adapter: the create-user sequence (POST, re-fetch,
 * reset-password, field re-assertion, cleanup on a failed follow-up), the create-time rejection
 * translation, single-user fetch and realm-role resolution. Package-private, like {@link
 * KeycloakAdminSupport}; {@link KeycloakAdminClient} extends it and exposes the public port methods
 * built on these helpers.
 */
abstract class KeycloakUserAdminSupport extends KeycloakIdentityProviderAdmin {

    private static final ObjectMapper OBJECT_MAPPER = JsonMappers.plain();

    KeycloakUserAdminSupport(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String realm,
            String clientId,
            String clientSecret) {
        super(restClientBuilder, baseUrl, realm, clientId, clientSecret);
    }

    /** Deletes a user - {@link #createUser} uses it to clean up after a failed follow-up step. */
    public abstract void deleteUser(String userId);

    /**
     * Token-reusing variant of {@link KeycloakAdminClient#getUserById}, so {@link
     * KeycloakAdminClient#setUserAttributes}'s read-then-write pair costs one admin token rather
     * than two (this class fetches a fresh token per public method call - see the class javadoc).
     * Unlike the bulk list endpoint, the single-user GET returns the FULL representation including
     * {@code attributes}, with no {@code briefRepresentation} parameter needed (cf. {@link
     * KeycloakAdminClient#listUsers}).
     */
    final KeycloakUserRepresentation fetchUser(String userId, String token) {
        KeycloakUserRepresentation user =
                call(
                        () ->
                                restClient
                                        .get()
                                        .uri("/admin/realms/{realm}/users/{userId}", realm, userId)
                                        .headers(authHeaders(token))
                                        .retrieve()
                                        .body(KeycloakUserRepresentation.class),
                        "get user by id");
        if (user == null) {
            throw new KeycloakAdminException("No user found with id " + userId);
        }
        return user;
    }

    final KeycloakUserRepresentation createUser(
            String username,
            String email,
            String firstName,
            String lastName,
            List<KeycloakCredentialRepresentation> credentials,
            Map<String, List<String>> attributes,
            List<String> requiredActions) {
        String token = obtainAdminToken();
        // Email verification is ENFORCED (2026-07-25): new users are created
        // with emailVerified=false + a VERIFY_EMAIL required action, so a person
        // must click the verification link before their first dashboard login
        // (realm verifyEmail=true backs this up). This was previously reverted
        // (2026-07-16) ONLY because the realm had no smtpServer, which made the
        // verify step uncompletable and permanently blocked login. That is no
        // longer true: SMTP is configured (Porkbun - deploy/.env KEYCLOAK_SMTP_*),
        // SPF/DMARC are set so mail reaches inboxes, and the realm frontendUrl
        // pins email links to https://auth.staging.example.com so the link resolves.
        // Do NOT flip back to emailVerified=true without a working mail path.
        KeycloakUserRepresentation newUser =
                new KeycloakUserRepresentation(
                        // B3: credentials are deliberately NOT sent in the create body. They are
                        // applied exactly once,
                        // by the /reset-password call below. Sending them here AND resetting
                        // afterwards set the same
                        // password twice, which a realm with a passwordHistory policy rejects
                        // (invalidPasswordHistoryMessage -> signup 502).
                        null,
                        username,
                        email,
                        firstName,
                        lastName,
                        true,
                        false,
                        List.of(),
                        attributes,
                        requiredActions);
        callCreateUser(
                () ->
                        restClient
                                .post()
                                .uri("/admin/realms/{realm}/users", realm)
                                .headers(authHeaders(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(newUser)
                                .retrieve()
                                .toBodilessEntity());

        // Keycloak's create-user endpoint returns 201 with a Location header,
        // not the created representation - re-fetch by username to get the
        // generated id back, same reasoning any REST client needs here.
        KeycloakUserRepresentation created =
                call(
                        () -> {
                            List<KeycloakUserRepresentation> matches =
                                    restClient
                                            .get()
                                            .uri(
                                                    "/admin/realms/{realm}/users?username={username}&exact=true",
                                                    realm,
                                                    username)
                                            .headers(authHeaders(token))
                                            .retrieve()
                                            .body(
                                                    new ParameterizedTypeReference<
                                                            List<KeycloakUserRepresentation>>() {});
                            if (matches == null || matches.isEmpty()) {
                                throw new KeycloakAdminException(
                                        "User '"
                                                + username
                                                + "' was created but could not be re-fetched");
                            }
                            return matches.get(0);
                        },
                        "re-fetch created user");

        // Explicit follow-up call, found necessary by testing: Keycloak's
        // create-user endpoint accepts a `credentials` array embedded in the
        // request body, but reliably applying it at creation time is a
        // known trouble spot in Keycloak's admin API (e.g.
        // https://github.com/keycloak/keycloak/issues/38177) - it can
        // create the user successfully while silently not setting the
        // password, leaving an account that exists but can never log in
        // ("Invalid username or password" even with the correct password).
        // Reproduced exactly this way testing Stage 5 signup end-to-end.
        // /reset-password is the one admin API surface documented and
        // consistently reliable for setting a credential, so every created
        // user's password is set there explicitly rather than trusting the
        // creation-time credentials array alone - which is why the create body above no longer
        // carries
        // credentials at all (setting the same password twice trips passwordHistory, see B3).
        // B4: once the user exists, a failing follow-up step must not leave it behind - the caller
        // never
        // receives the id, so it could not clean the account up itself (signup compensation saw a
        // null id).
        try {
            if (!credentials.isEmpty()) {
                resetPassword(created.id(), token, credentials.get(0));
            }

            // Real bug, found 2026-07-16: the exact same "embedded field silently
            // not applied at creation time" Keycloak admin API trouble spot
            // documented above for `credentials` also affects `attributes`,
            // `firstName`/`lastName`, AND (confirmed via check-user-profile-
            // config.ps1 - a live user had firstName/lastName correctly set but
            // email='') `email` too - basically every field this constructor
            // hands to the create-user POST body is unreliable at creation time,
            // not just the ones caught so far. Three distinct symptoms traced to
            // this one root cause: (1) attributes empty -> JWT with no tenant_id
            // claim, rejected by TenantContextFilter's "Your account is not
            // linked to an organization" 403 (see scripts/keycloak/check-tenant-claim.ps1); (2/3)
            // firstName/lastName/email blank -> fails Keycloak's default
            // declarative User Profile's required-field check (email and both
            // names are required-for-role-"user" per that config), so every
            // login gets redirected to a VERIFY_PROFILE ("Update Account
            // Information") required-action screen - and critically, Keycloak
            // evaluates this dynamically against the User Profile schema on
            // every login, not from a stored per-user flag, so it keeps firing
            // until every required field actually has a value, not just until
            // requiredActions is cleared. Same fix shape as resetPassword: one
            // explicit follow-up PUT re-applying every field this constructor
            // was given, not trusting the creation-time payload alone.
            // Unconditional - always redundant-but-harmless when Keycloak did
            // apply them at creation, always the actual fix when it didn't.
            patchUserFields(created.id(), token, email, firstName, lastName, attributes);
        } catch (RuntimeException followUpFailure) {
            try {
                deleteUser(created.id());
            } catch (RuntimeException cleanupFailure) {
                followUpFailure.addSuppressed(cleanupFailure);
            }
            throw followUpFailure;
        }

        return created;
    }

    /**
     * Creation-time only - {@link #createUser} is its sole caller, and that is what keeps it safe.
     * It sends the same "attributes map alongside a partial root body" shape that made {@link
     * KeycloakAdminClient#setUserAttributes} destructive (see that method's javadoc: since Keycloak
     * 24.0.4 an {@code attributes} map in the body IS the user's whole attribute set), but here the
     * target is a user created seconds earlier with exactly this attribute set, so there is nothing
     * pre-existing to drop, and the three root fields it does carry are the very ones being
     * repaired. Do NOT reuse this to update an already-live user; use {@link
     * KeycloakAdminClient#setUserAttributes}, which merges.
     */
    private void patchUserFields(
            String userId,
            String token,
            String email,
            String firstName,
            String lastName,
            Map<String, List<String>> attributes) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("email", email);
        patch.put("firstName", firstName);
        patch.put("lastName", lastName);
        if (attributes != null && !attributes.isEmpty()) {
            patch.put("attributes", attributes);
        }
        call(
                () ->
                        restClient
                                .put()
                                .uri("/admin/realms/{realm}/users/{userId}", realm, userId)
                                .headers(authHeaders(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(patch)
                                .retrieve()
                                .toBodilessEntity(),
                "patch user fields (firstName/lastName/attributes)");
    }

    private void resetPassword(
            String userId, String token, KeycloakCredentialRepresentation credential) {
        call(
                () ->
                        restClient
                                .put()
                                .uri(
                                        "/admin/realms/{realm}/users/{userId}/reset-password",
                                        realm,
                                        userId)
                                .headers(authHeaders(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(credential)
                                .retrieve()
                                .toBodilessEntity(),
                "reset user password");
    }

    final List<KeycloakRoleRepresentation> resolveRoles(Set<String> roleNames, String token) {
        List<KeycloakRoleRepresentation> resolved = new ArrayList<>();
        for (String roleName : roleNames) {
            KeycloakRoleRepresentation role =
                    call(
                            () ->
                                    restClient
                                            .get()
                                            .uri(
                                                    "/admin/realms/{realm}/roles/{roleName}",
                                                    realm,
                                                    roleName)
                                            .headers(authHeaders(token))
                                            .retrieve()
                                            .body(KeycloakRoleRepresentation.class),
                            "resolve role '" + roleName + "'");
            if (role == null) {
                throw new KeycloakAdminException("Realm role '" + roleName + "' does not exist");
            }
            resolved.add(role);
        }
        return resolved;
    }

    static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * {@code createUser}'s POST-only variant of {@link #call}. Keycloak's two routine,
     * admin-actionable rejections get translated into typed exceptions carrying clean messages
     * instead of the generic "upstream failed, here's the raw body" 502 - real prod failures
     * observed 2026-10-01:
     *
     * <ul>
     *   <li>409 {@code {"errorMessage":"User exists with same email"}} (or same username) - a
     *       duplicate the admin can fix, not a service outage. Becomes {@link
     *       KeycloakUserConflictException} (409).
     *   <li>400 {@code {"field","errorMessage","params"}} from the realm's declarative User Profile
     *       validation (e.g. username error-invalid-length) - bad input, not an upstream fault.
     *       Becomes {@link KeycloakUserValidationException} (400).
     * </ul>
     *
     * Every other status keeps the generic {@link KeycloakAdminException} shape {@link #call}
     * produces.
     */
    private <T> T callCreateUser(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            throw translateCreateUserRejection(e);
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Could not reach Keycloak (create user): " + e.getMessage(), e);
        }
    }

    private KeycloakAdminException translateCreateUserRejection(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        if (status == 409) {
            if (body != null && body.contains("same email")) {
                return new KeycloakUserConflictException(
                        "A user with this email already exists.", e);
            }
            if (body != null && body.contains("username")) {
                return new KeycloakUserConflictException(
                        "A user with this username already exists.", e);
            }
            return new KeycloakUserConflictException(
                    "A user with these details already exists.", e);
        }
        if (status == 400) {
            return new KeycloakUserValidationException(describeUserProfileError(body), e);
        }
        return new KeycloakAdminException(
                "Keycloak admin API call failed (create user): HTTP "
                        + status
                        + " - "
                        + body
                        + OPS_HINT,
                e);
    }

    /**
     * Renders Keycloak's User Profile rejection body ({@code {"field":
     * "username", "errorMessage": "error-invalid-length", "params":
     * ["username", 3, 255]}) as something an admin can act on. Falls back to
     * a generic line when the body is missing or shaped differently than the
     * known form - never throws.
     */
    private static String describeUserProfileError(String body) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            String field = node.path("field").asText(null);
            String errorMessage = node.path("errorMessage").asText(null);
            JsonNode params = node.path("params");
            if ("error-invalid-length".equals(errorMessage)
                    && params.isArray()
                    && params.size() >= 3) {
                String label = field == null || field.isBlank() ? "This field" : capitalize(field);
                return label
                        + " must be between "
                        + params.get(1).asText()
                        + " and "
                        + params.get(2).asText()
                        + " characters.";
            }
            if (field != null && !field.isBlank()) {
                return "Invalid " + field + (errorMessage == null ? "." : ": " + errorMessage);
            }
        } catch (Exception ignored) {
            // Unparseable body - the generic fallback below still beats raw JSON.
        }
        return "The user details were rejected - check username and email, then try again.";
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
