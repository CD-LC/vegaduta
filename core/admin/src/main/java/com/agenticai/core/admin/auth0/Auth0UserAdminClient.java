package com.agenticai.core.admin.auth0;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakAdminException;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.admin.keycloak.KeycloakUserValidationException;
import com.agenticai.core.admin.keycloak.TenantAssignableRoles;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Auth0 implementation of {@link IdentityUserAdminPort} over the Management API v2
 * ({@code https://{domain}/api/v2}), for deployments where Auth0 is the identity provider instead
 * of Keycloak. Selected by {@code app.identity.provider=auth0}; {@link
 * com.agenticai.core.admin.keycloak.KeycloakAdminClient} stays the default
 * ({@code matchIfMissing = true} on its own condition), so exactly one {@code
 * IdentityUserAdminPort} bean exists.
 *
 * <p>Authenticates as an Auth0 <b>Machine-to-Machine application</b> authorized against the
 * Management API (client_credentials on {@code POST /oauth/token}), the Auth0 analogue of
 * Keycloak's {@code agentic-ai-core} service account. The token IS cached until shortly before its
 * {@code expires_in} - unlike the Keycloak adapter's documented every-call refetch - because the
 * Management API rate-limits token issuance aggressively. Required Management API scopes on the
 * M2M application: {@code read:users}, {@code create:users}, {@code update:users}, {@code
 * delete:users}, {@code read:roles}, {@code delete:sessions}, {@code delete:refresh_tokens}. The
 * same Auth0 application must additionally have the database {@code connection} enabled - {@link
 * #sendActionsEmail} and {@link #createUser} send Auth0's own password-reset email through {@code
 * POST /dbconnections/change_password} (Authentication API), which requires it.
 *
 * <p>Field mapping onto the shared {@link KeycloakUserRepresentation} model is documented on
 * {@link Auth0UserRepresentation}; the load-bearing one is {@code app_metadata.tenant_id}, the
 * Auth0 home of Keycloak's {@code tenant_id} user attribute.
 *
 * <p><b>Scope of this adapter:</b> user admin only. {@code IdentityProviderAdminPort}
 * (per-tenant SSO connection / social-IdP management) is not implemented - its consumers
 * ({@code TenantSsoService}, {@code TenantSsoController}, {@code SocialAuthConfigController})
 * are gated on {@code app.identity.provider=keycloak} and are simply absent under Auth0.
 *
 * <p><b>Known differences from the Keycloak adapter, stated honestly:</b>
 *
 * <ul>
 *   <li>Auth0 caps user search at 1000 results (paging beyond that is rejected), so {@link
 *       #listUsers} / {@link #listUsersByTenant} / {@link #listUsersGroupedByTenant} silently
 *       truncate past 1000 users - fine at the tenant counts this platform manages.
 *   <li>Auth0 has no "temporary password" flag - {@link #createUser} approximates it by also
 *       sending Auth0's password-reset email, so the invitee can set their own password even if
 *       the admin-typed one is never relayed.
 *   <li>Token-side mapping (roles claim, tenant claim, issuer, JWKS) is NOT this class - it is
 *       the already-merged {@code app.security.oidc.*} claim mapping plus {@code
 *       KEYCLOAK_ISSUER_URI}/{@code KEYCLOAK_JWK_SET_URI}; see docs/configuration.md.
 * </ul>
 */
@Component
@Profile("security")
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "auth0")
public class Auth0UserAdminClient extends Auth0ManagementSupport
        implements IdentityUserAdminPort {

    private static final SecureRandom RANDOM = new SecureRandom();
    /**
     * @param restClientBuilder the AUTO-CONFIGURED builder - injected, never a bare {@code
     *     RestClient.builder()}, so {@code spring.http.client.*} timeouts apply (same fix as
     *     {@link com.agenticai.core.admin.keycloak.KeycloakAdminClient}'s constructor javadoc).
     * @param domain the Auth0 tenant domain; a bare host ({@code my-tenant.auth0.com}) or a full
     *     URL ({@code https://my-tenant.auth0.com}) - normalized in the constructor.
     */
    public Auth0UserAdminClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.identity.auth0.domain}") String domain,
            @Value("${app.identity.auth0.client-id}") String clientId,
            @Value("${app.identity.auth0.client-secret}") String clientSecret,
            @Value("${app.identity.auth0.connection:Username-Password-Authentication}")
                    String connection,
            @Value("${app.identity.auth0.audience:}") String audience) {
        super(restClientBuilder, domain, clientId, clientSecret, connection, audience);
    }

    @Override
    public List<KeycloakUserRepresentation> listUsers() {
        return pagedUsers(obtainManagementToken(), null).stream()
                .map(Auth0UserRepresentation::toPortUser)
                .toList();
    }

    /**
     * Server-side Lucene filter on {@code app_metadata.tenant_id} - the place this adapter puts
     * Keycloak's {@code tenant_id} user attribute - rather than the Keycloak adapter's
     * fetch-everything-then-filter-in-Java. The query shape is documented Auth0 user-search v3;
     * results cap at 1000 users per query (class javadoc).
     */
    @Override
    public List<KeycloakUserRepresentation> listUsersByTenant(String tenantId) {
        String query = "app_metadata.tenant_id:\"" + escapeLucene(tenantId) + "\"";
        return pagedUsers(obtainManagementToken(), query).stream()
                .map(Auth0UserRepresentation::toPortUser)
                .toList();
    }

    /**
     * Same {@code tenant_id} grouping as the Keycloak adapter, over one paged Auth0 listing (the
     * platform roster's per-tenant figures). Users with no {@code app_metadata.tenant_id} are
     * omitted.
     */
    @Override
    public Map<String, List<KeycloakUserRepresentation>> listUsersGroupedByTenant() {
        Map<String, List<KeycloakUserRepresentation>> byTenant = new HashMap<>();
        for (KeycloakUserRepresentation user : listUsers()) {
            if (user.attributes() == null) {
                continue;
            }
            for (String tenant : user.attributes().getOrDefault("tenant_id", List.of())) {
                if (tenant != null && !tenant.isBlank()) {
                    byTenant.computeIfAbsent(tenant, k -> new ArrayList<>()).add(user);
                }
            }
        }
        return byTenant;
    }

    @Override
    public int countUsersByTenant(String tenantId) {
        return listUsersByTenant(tenantId).size();
    }

    /**
     * Lucene {@code email:"..."} lookup - Auth0's {@code email} field is unique per database
     * connection, so the first hit is the account. Empty {@code Optional} (never an exception)
     * when nothing matches, same contract as the Keycloak adapter.
     */
    @Override
    public Optional<KeycloakUserRepresentation> findByEmail(String email) {
        List<Auth0UserRepresentation> matches =
                call(
                        () ->
                                restClient
                                        .get()
                                        .uri(
                                                "/api/v2/users?q={q}&search_engine=v3&per_page=1&page=0",
                                                "email:\"" + escapeLucene(email) + "\"")
                                        .headers(authHeaders(obtainManagementToken()))
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        List<Auth0UserRepresentation>>() {}),
                        "find user by email");
        return matches == null || matches.isEmpty()
                ? Optional.empty()
                : Optional.of(matches.get(0).toPortUser());
    }

    @Override
    public KeycloakUserRepresentation getUserById(String userId) {
        return fetchUser(userId, obtainManagementToken()).toPortUser();
    }

    /**
     * {@code GET /api/v2/users/{id}/roles} - the Auth0 equivalent of Keycloak's realm role
     * mappings (Auth0 has no realm/client split; "realm roles" map to tenant-level Auth0 roles).
     */
    @Override
    public List<String> getUserRealmRoleNames(String userId) {
        List<Auth0RoleRepresentation> roles =
                call(
                        () ->
                                restClient
                                        .get()
                                        .uri(
                                                "/api/v2/users/{id}/roles?per_page={size}",
                                                userId,
                                                USER_PAGE_SIZE)
                                        .headers(authHeaders(obtainManagementToken()))
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        List<Auth0RoleRepresentation>>() {}),
                        "get user roles");
        return roles == null
                ? List.of()
                : roles.stream().map(Auth0RoleRepresentation::name).toList();
    }

    /**
     * Admin-portal user creation ({@code UserAdminController}). Auth0 has no "temporary password"
     * flag (Keycloak's {@code temporary=true} credential), so this adapter approximates the
     * intent: the admin-typed password is set verbatim, then {@link #sendPasswordChangeEmail}
     * sends Auth0's own reset email, letting the invitee set their own password even if the
     * temporary one is never relayed. The user's account works immediately either way - a real
     * semantic gap versus Keycloak's forced password change on first login, which Auth0 cannot
     * express on a database-connection user.
     */
    @Override
    public KeycloakUserRepresentation createUser(
            String username,
            String email,
            String firstName,
            String lastName,
            String temporaryPassword,
            String tenantId) {
        KeycloakUserRepresentation created =
                createAuth0User(
                        username,
                        email,
                        firstName,
                        lastName,
                        temporaryPassword,
                        Map.of("tenant_id", tenantId),
                        true);
        sendPasswordChangeEmail(created.id(), obtainManagementToken());
        return created;
    }

    /**
     * Invite-by-email variant ({@code UserAdminController}, {@code ScimProvisioningController}).
     * Auth0's database connections cannot create a user without a password, so a random one is
     * minted and never returned or logged - the invitee sets a real one through the reset email
     * {@link #sendActionsEmail} sends for {@code UPDATE_PASSWORD}. {@code verify_email} is left
     * false here because the caller's {@code VERIFY_EMAIL} action triggers the verification-email
     * job; setting both would email the user twice.
     */
    @Override
    public KeycloakUserRepresentation createUserForInvite(
            String username, String email, String firstName, String lastName, String tenantId) {
        return createAuth0User(
                username,
                email,
                firstName,
                lastName,
                randomPassword(),
                Map.of("tenant_id", tenantId),
                false);
    }

    /**
     * Auth0 counterpart of Keycloak's execute-actions email - mapped by action name, since Auth0
     * has no generic "perform these required actions" link:
     *
     * <ul>
     *   <li>{@code UPDATE_PASSWORD} -&gt; {@code POST /dbconnections/change_password}
     *       (Authentication API): Auth0 itself emails the user a password-reset link for the
     *       configured connection. The ticket alternative ({@code POST
     *       /api/v2/tickets/password-change}) only returns a bare URL the platform would have to
     *       deliver itself, and this service has no mailer - the emailed reset is the closest
     *       semantic equivalent to Keycloak sending the execute-actions email. Requires the M2M
     *       {@code client_id} to have the database connection enabled.
     *   <li>{@code VERIFY_EMAIL} -&gt; {@code POST /api/v2/jobs/verification-email} (Management
     *       API): Auth0's own verification email for the user.
     *   <li>Any other action name -&gt; {@link KeycloakUserValidationException}: better a clean
     *       400 than silently pretending an action was delivered.
     * </ul>
     */
    @Override
    public void sendActionsEmail(String userId, List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        String token = obtainManagementToken();
        for (String action : actions) {
            switch (action) {
                case "UPDATE_PASSWORD" -> sendPasswordChangeEmail(userId, token);
                case "VERIFY_EMAIL" -> sendVerificationEmail(userId, token);
                default ->
                        throw new KeycloakUserValidationException(
                                "Auth0 has no equivalent for required action '" + action + "'",
                                null);
            }
        }
    }

    /**
     * Auth0's emailed password reset: {@code POST /dbconnections/change_password} on the tenant
     * domain (Authentication API, not Management - no bearer token needed, the {@code client_id}
     * is the credential). Auth0 generates the reset link and emails it to {@code email} itself.
     */
    private void sendPasswordChangeEmail(String userId, String token) {
        Auth0UserRepresentation user = fetchUser(userId, token);
        if (user.email() == null || user.email().isBlank()) {
            throw new KeycloakAdminException(
                    "User " + userId + " has no email address - cannot send a reset email");
        }
        call(
                () ->
                        restClient
                                .post()
                                .uri("/dbconnections/change_password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        Map.of(
                                                "client_id", clientId,
                                                "email", user.email(),
                                                "connection", connection))
                                .retrieve()
                                .toBodilessEntity(),
                "send password-change email");
    }

    private void sendVerificationEmail(String userId, String token) {
        call(
                () ->
                        restClient
                                .post()
                                .uri("/api/v2/jobs/verification-email")
                                .headers(authHeaders(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("user_id", userId, "client_id", clientId))
                                .retrieve()
                                .toBodilessEntity(),
                "send verification email");
    }

    /**
     * Self-service signup ({@code TenantProvisioningService}): the user-chosen password is set
     * verbatim and {@code app_metadata} carries the whole attributes map ({@code tenant_id},
     * {@code organization_name}), so the very first Auth0-issued token can already carry the
     * tenant claim - same guarantee the Keycloak path makes.
     */
    @Override
    public KeycloakUserRepresentation createUserForSignup(
            String username,
            String email,
            String firstName,
            String lastName,
            String permanentPassword,
            Map<String, String> attributes) {
        return createAuth0User(
                username, email, firstName, lastName, permanentPassword, attributes, true);
    }

    private KeycloakUserRepresentation createAuth0User(
            String username,
            String email,
            String firstName,
            String lastName,
            String password,
            Map<String, String> appMetadata,
            boolean sendVerificationEmail) {
        String token = obtainManagementToken();
        Map<String, Object> body = new HashMap<>();
        body.put("connection", connection);
        body.put("email", email);
        body.put("password", password);
        // The `username` field is only accepted when the connection has requires_username
        // enabled (a 400 otherwise), and when it merely duplicates the email it buys nothing -
        // Auth0 database users log in with email anyway.
        if (username != null && !username.isBlank() && !username.equals(email)) {
            body.put("username", username);
        }
        putIfNotNull(body, "given_name", firstName);
        putIfNotNull(body, "family_name", lastName);
        String fullName =
                java.util.stream.Stream.of(firstName, lastName)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.joining(" "));
        putIfNotNull(body, "name", fullName.isBlank() ? null : fullName);
        // email_verified=false + verify_email=true: Auth0 emails its verification link at
        // creation, mirroring Keycloak's VERIFY_EMAIL required action.
        body.put("email_verified", false);
        body.put("verify_email", sendVerificationEmail);
        if (appMetadata != null && !appMetadata.isEmpty()) {
            body.put("app_metadata", new HashMap<>(appMetadata));
        }
        Auth0UserRepresentation created =
                callCreateUser(
                        () ->
                                restClient
                                        .post()
                                        .uri("/api/v2/users")
                                        .headers(authHeaders(token))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(body)
                                        .retrieve()
                                        .body(Auth0UserRepresentation.class));
        if (created == null || created.userId() == null) {
            throw new KeycloakAdminException(
                    "Auth0 created the user but returned no user_id for '" + username + "'");
        }
        return created.toPortUser();
    }

    /**
     * The never-relayed placeholder password for {@link #createUserForInvite} - Auth0 database
     * connections require a password at creation. 128 bits of UUID entropy plus mixed
     * upper/lower/digit/symbol characters to satisfy Auth0's strongest built-in password policy;
     * the real credential is the one the invitee sets through the reset email.
     */
    private static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "") + "Aa1!";
    }

    /**
     * Merges {@code attributes} into {@code app_metadata} ({@code tenant_id}, {@code
     * organization_name}) on an existing user. Unlike the Keycloak adapter this needs no
     * read-modify-write: Auth0's {@code PATCH /api/v2/users/{id}} merges {@code app_metadata}
     * keys at the top level rather than replacing them (documented Auth0 behaviour), and any
     * other root field left out of the body is unchanged.
     *
     * <p>One Auth0 edge case IS guarded: a PATCH carrying {@code "app_metadata": {}} WIPES all
     * app metadata rather than merging nothing (documented Auth0 quirk), so an empty attributes
     * map returns early as a no-op instead of erasing the user's {@code tenant_id}.
     */
    @Override
    public void setUserAttributes(String userId, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        String token = obtainManagementToken();
        call(
                () ->
                        restClient
                                .patch()
                                .uri("/api/v2/users/{id}", userId)
                                .headers(authHeaders(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("app_metadata", attributes))
                                .retrieve()
                                .toBodilessEntity(),
                "set user attributes");
    }

    /**
     * {@code PATCH /api/v2/users/{id}} with {@code {"blocked": ...}} - Auth0's inverse of
     * Keycloak's {@code enabled} flag: a blocked user cannot authenticate but keeps their account
     * and data.
     */
    @Override
    public void setUserEnabled(String userId, boolean enabled) {
        String token = obtainManagementToken();
        call(
                () ->
                        restClient
                                .patch()
                                .uri("/api/v2/users/{id}", userId)
                                .headers(authHeaders(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("blocked", !enabled))
                                .retrieve()
                                .toBodilessEntity(),
                "set user enabled");
    }

    /**
     * {@code DELETE /api/v2/users/{id}/sessions} then {@code DELETE
     * /api/v2/users/{id}/refresh-tokens} - together the Auth0 equivalent of Keycloak's
     * user-logout endpoint: server sessions die (cookie logins end at once) and refresh tokens
     * stop minting new access tokens. Both are attempted even if the first fails (partial
     * revocation beats none); the first failure is rethrown with the second suppressed. Needs
     * {@code delete:sessions} and {@code delete:refresh_tokens} scopes. Same caveat as Keycloak:
     * already-issued JWT access tokens stay valid until their own expiry - Auth0 has no push
     * revocation for stateless JWTs either.
     */
    @Override
    public void logoutUser(String userId) {
        String token = obtainManagementToken();
        RuntimeException failure = null;
        for (String child : List.of("sessions", "refresh-tokens")) {
            try {
                call(
                        () ->
                                restClient
                                        .delete()
                                        .uri("/api/v2/users/{id}/" + child, userId)
                                        .headers(authHeaders(token))
                                        .retrieve()
                                        .toBodilessEntity(),
                        "delete user " + child);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * {@code DELETE /api/v2/users/{id}} - same compensating role as the Keycloak adapter's
     * delete (tenant-provisioning failure must not leave an orphaned account).
     */
    @Override
    public void deleteUser(String userId) {
        String token = obtainManagementToken();
        call(
                () ->
                        restClient
                                .delete()
                                .uri("/api/v2/users/{id}", userId)
                                .headers(authHeaders(token))
                                .retrieve()
                                .toBodilessEntity(),
                "delete user");
    }

    /**
     * Full-set replace of a user's roles, same contract as the Keycloak adapter (adds missing,
     * removes extra - not an additive-only grant). Auth0 role endpoints take role <b>ids</b>, so
     * desired and current names are resolved against {@code GET /api/v2/roles} first; an
     * unresolvable name fails with {@link KeycloakAdminException} like Keycloak's
     * resolve-by-name step. Auth0 has no equivalent of Keycloak's per-realm
     * {@code default-roles-*} composite, so no default-role exclusion is needed. The {@link
     * TenantAssignableRoles} guard is kept verbatim - it is the privilege-escalation fix, not a
     * Keycloak detail.
     */
    @Override
    public void replaceUserRealmRoles(String userId, Set<String> desiredRoleNames) {
        TenantAssignableRoles.requireAllAssignable(desiredRoleNames);
        String token = obtainManagementToken();

        List<Auth0RoleRepresentation> currentRoles = userRoles(userId, token);
        Map<String, String> roleIdsByName = roleIdsByName(token);

        Set<String> currentNames =
                currentRoles.stream()
                        .map(Auth0RoleRepresentation::name)
                        .collect(Collectors.toSet());
        Set<String> toAdd = new HashSet<>(desiredRoleNames);
        toAdd.removeAll(currentNames);
        Set<String> toRemove = new HashSet<>(currentNames);
        toRemove.removeAll(desiredRoleNames);

        if (!toAdd.isEmpty()) {
            call(
                    () ->
                            restClient
                                    .post()
                                    .uri("/api/v2/users/{id}/roles", userId)
                                    .headers(authHeaders(token))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(Map.of("roles", resolveRoleIds(toAdd, roleIdsByName)))
                                    .retrieve()
                                    .toBodilessEntity(),
                    "add user roles");
        }
        if (!toRemove.isEmpty()) {
            Map<String, String> currentIdsByName =
                    currentRoles.stream()
                            .collect(
                                    Collectors.toMap(
                                            Auth0RoleRepresentation::name,
                                            Auth0RoleRepresentation::id));
            List<String> removeIds =
                    toRemove.stream().map(currentIdsByName::get).toList();
            call(
                    () ->
                            restClient
                                    .method(HttpMethod.DELETE)
                                    .uri("/api/v2/users/{id}/roles", userId)
                                    .headers(authHeaders(token))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(Map.of("roles", removeIds))
                                    .retrieve()
                                    .toBodilessEntity(),
                    "remove user roles");
        }
    }
}
