package com.agenticai.core.admin.keycloak;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper over Keycloak's Admin REST API (not the full {@code keycloak-admin-client} SDK -
 * that pulls in its own RESTEasy client stack alongside Spring's; a handful of endpoints
 * hand-called with {@link RestClient} keeps this dependency-light and consistent with {@link
 * com.agenticai.core.knowledge.SidecarClient}'s pattern elsewhere in this codebase).
 *
 * <p>Authenticates as the {@code agentic-ai-core} client's own service account (client_credentials
 * grant) - see {@code application-security.yml}'s comments for the one-time Keycloak admin-console
 * step (granting that service account the {@code realm-management} client's {@code manage-users}
 * and {@code view-users} roles) this depends on.
 *
 * <p><b>Known limitation, stated honestly:</b> fetches a fresh admin token on every public method
 * call, no caching. Fine at admin-panel usage volumes (a human clicking around, not a hot path),
 * worth adding expiry-aware caching before this sees meaningful traffic.
 *
 * <p>Bean selection: {@code app.identity.provider=keycloak} (or the property unset) creates this
 * adapter; {@code auth0} creates {@link com.agenticai.core.admin.auth0.Auth0UserAdminClient}
 * instead, so exactly one {@code IdentityUserAdminPort} bean exists per deployment.
 */
@Component
@Profile("security")
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "keycloak", matchIfMissing = true)
public class KeycloakAdminClient extends KeycloakUserAdminSupport
        implements IdentityUserAdminPort {

    /** {@link #listUsers} page size - Keycloak's own implicit cap when {@code max} is omitted. */
    static final int USER_PAGE_SIZE = 100;

    /**
     * Hard ceiling on {@link #listUsers} pages (100k users) - a safety stop, not a business limit.
     */
    static final int MAX_USER_PAGES = 1_000;

    /**
     * @param restClientBuilder the AUTO-CONFIGURED builder, injected rather than a bare {@code
     *     RestClient.builder()} (2026-07-29 security audit). A hand-built one bypasses Spring
     *     Boot's HTTP client auto-configuration, so this client ran with no connect and no read
     *     timeout at all - and every public method here fetches a fresh admin token first (see this
     *     class's javadoc), so an unresponsive Keycloak hung the calling admin request forever
     *     rather than failing it. Injecting the shared builder picks up {@code
     *     spring.http.client.*}.
     */
    public KeycloakAdminClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.keycloak.admin-base-url}") String baseUrl,
            @Value("${app.keycloak.realm}") String realm,
            @Value("${app.keycloak.client-id}") String clientId,
            @Value("${app.keycloak.client-secret}") String clientSecret) {
        super(restClientBuilder, baseUrl, realm, clientId, clientSecret);
    }

    /**
     * Whether a new user must verify their email before first sign-in. On (the default) a new user
     * gets the {@code VERIFY_EMAIL} required action, which needs the realm to be able to send mail.
     * A self-hosted install with no SMTP (the lite tier) turns it off, because otherwise the very
     * first sign-up ends on "Failed to send email" and the person can never sign in.
     */
    @Value("${app.keycloak.require-email-verification:true}")
    private boolean requireEmailVerification = true;

    private List<String> verifyEmailActions() {
        return requireEmailVerification ? List.of("VERIFY_EMAIL") : List.of();
    }

    @Override
    public List<KeycloakUserRepresentation> listUsers() {
        String token = obtainAdminToken();
        // briefRepresentation=false is required here, not cosmetic: Keycloak's
        // bulk list-users endpoint defaults briefRepresentation to true, which
        // explicitly OMITS the "attributes" field from every returned user
        // (per Keycloak's own Admin REST API docs - brief mode only guarantees
        // id/username/name/email/enabled/etc., not attributes or credentials).
        // listUsersByTenant() below filters on user.attributes() - without
        // this parameter, attributes() is always null for every user here,
        // so that filter silently matches nothing, no matter how correctly
        // tenant_id is set in Keycloak. A real bug that predates this fix:
        // every earlier "no users showing" symptom in this project had a
        // different, already-fixed cause (a 500 upstream of ever reaching
        // this method) masking the fact that this call itself was broken.
        //
        // Paged (2026-10-09): Keycloak's list-users endpoint returns at most
        // 100 users when no `max` is given, silently. Every caller here (the
        // tenant user list, per-tenant counts, the platform users page,
        // tenant erasure's "delete every Keycloak user of this tenant") read
        // that first page as the whole realm - past 100 users, counts were
        // wrong and erasure could leave accounts behind. Pages of
        // USER_PAGE_SIZE until a short page, under one admin token, with a
        // hard page ceiling so a misbehaving server can never loop forever.
        List<KeycloakUserRepresentation> all = new ArrayList<>();
        for (int page = 0; page < MAX_USER_PAGES; page++) {
            int first = page * USER_PAGE_SIZE;
            List<KeycloakUserRepresentation> batch =
                    call(
                            () ->
                                    restClient
                                            .get()
                                            .uri(
                                                    "/admin/realms/{realm}/users?briefRepresentation=false&first={first}&max={max}",
                                                    realm,
                                                    first,
                                                    USER_PAGE_SIZE)
                                            .headers(authHeaders(token))
                                            .retrieve()
                                            .body(
                                                    new ParameterizedTypeReference<
                                                            List<KeycloakUserRepresentation>>() {}),
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

    /**
     * Every user in the realm grouped by their {@code tenant_id} attribute (the tenant SCHEMA name)
     * - one paged realm read for callers that need per-tenant figures for many tenants at once (the
     * platform roster), in place of one {@link #countUsersByTenant} realm read per tenant. Users
     * with no {@code tenant_id} are omitted.
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

    /**
     * Tenant-scoped listing for {@link com.agenticai.core.admin.UserAdminController} - a real bug
     * caught testing Phase 0 Stage 5's signup flow end-to-end with two independently self-signed-up
     * tenants: the realm has been shared across all tenants since Stage 4 (see {@code
     * Tenant.java}'s javadoc), but this controller's {@code list()} was still calling the
     * unfiltered {@link #listUsers}, so any tenant-admin could see every user in every tenant.
     *
     * <p><b>Known limitation, stated honestly:</b> fetches every user in the realm and filters in
     * Java by the {@code tenant_id} attribute, rather than Keycloak's {@code ?q=tenant_id:value}
     * custom-attribute search query param - correctness here matters more than efficiency at Phase
     * 0 scale (an admin panel a human clicks around, not a hot path), and doing the filter
     * explicitly in code this class already controls is easier to be confident is actually correct
     * than trusting an admin-API query syntax this codebase has never exercised. Worth revisiting
     * once a single realm holds enough users for this to matter.
     */
    @Override
    public List<KeycloakUserRepresentation> listUsersByTenant(String tenantId) {
        return listUsers().stream()
                .filter(
                        user ->
                                user.attributes() != null
                                        && user.attributes()
                                                .getOrDefault("tenant_id", List.of())
                                                .contains(tenantId))
                .toList();
    }

    /**
     * Count-only variant of {@link #listUsersByTenant} for {@code
     * com.agenticai.core.platform.PlatformTenantMetricsService}'s cross-tenant roster (Ecosystem
     * Maintenance's per-tenant user counts) - deliberately just {@code .size()} on the existing
     * list call rather than a second, separately-maintained query path: this is admin-panel-volume
     * traffic (a super-admin occasionally loading one dashboard), not a hot path, so duplicating
     * {@link #listUsersByTenant}'s filtering logic here would only add a second place that same
     * tenant-scoping bug (see that method's own javadoc for the cross-tenant leak it once had)
     * could be reintroduced.
     */
    @Override
    public int countUsersByTenant(String tenantId) {
        return listUsersByTenant(tenantId).size();
    }

    /**
     * Phase 0 Stage 5: a proactive pre-check before attempting signup, so a duplicate email
     * produces a clean app-level error instead of parsing Keycloak's raw 409 response for "user
     * already exists" - {@code java.util.Optional} rather than throwing, since "not found" is an
     * entirely expected, non-exceptional result here (unlike {@link #getUserById}, which is always
     * called with an id this class itself just created or already knows to exist).
     */
    @Override
    public java.util.Optional<KeycloakUserRepresentation> findByEmail(String email) {
        String token = obtainAdminToken();
        List<KeycloakUserRepresentation> matches =
                call(
                        () ->
                                restClient
                                        .get()
                                        .uri(
                                                "/admin/realms/{realm}/users?email={email}&exact=true",
                                                realm,
                                                email)
                                        .headers(authHeaders(token))
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        List<KeycloakUserRepresentation>>() {}),
                        "find user by email");
        return matches == null || matches.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(matches.get(0));
    }

    @Override
    public KeycloakUserRepresentation getUserById(String userId) {
        return fetchUser(userId, obtainAdminToken());
    }

    @Override
    public List<String> getUserRealmRoleNames(String userId) {
        String token = obtainAdminToken();
        List<KeycloakRoleRepresentation> roles =
                call(
                        () ->
                                restClient
                                        .get()
                                        .uri(
                                                "/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                                                realm,
                                                userId)
                                        .headers(authHeaders(token))
                                        .retrieve()
                                        .body(
                                                new ParameterizedTypeReference<
                                                        List<KeycloakRoleRepresentation>>() {}),
                        "get user realm roles");
        return roles == null
                ? List.of()
                : roles.stream().map(KeycloakRoleRepresentation::name).toList();
    }

    /**
     * Admin-portal user creation ({@link com.agenticai.core.admin.UserAdminController}) - {@code
     * tenantId} is required and always set as the {@code tenant_id} attribute, same field
     * self-service signup sets (see {@link #createUserForSignup}). A second real bug caught
     * alongside {@link #listUsersByTenant}: this previously created users with no {@code tenant_id}
     * attribute at all, so anyone invited through the admin panel could never resolve to a tenant
     * schema on their first login - the tenant-admin's own invite flow was silently broken since
     * the Stage 4 shared-realm migration, just never exercised end-to-end until now.
     */
    @Override
    public KeycloakUserRepresentation createUser(
            String username,
            String email,
            String firstName,
            String lastName,
            String temporaryPassword,
            String tenantId) {
        return createUser(
                username,
                email,
                firstName,
                lastName,
                List.of(KeycloakCredentialRepresentation.temporaryPassword(temporaryPassword)),
                Map.of("tenant_id", List.of(tenantId)),
                verifyEmailActions());
    }

    /**
     * Invite-by-email variant of {@link #createUser} (granular-RBAC pass, 2026-09-23): no password
     * at all - the user sets their own via the action link {@link #sendActionsEmail} sends. {@code
     * UPDATE_PASSWORD} is carried as a required action so the invite email's link lands the user on
     * the set-password step (Keycloak executes pending required actions after it), with {@code
     * VERIFY_EMAIL} alongside for the same enforced-verification posture as the temp-password path.
     */
    @Override
    public KeycloakUserRepresentation createUserForInvite(
            String username, String email, String firstName, String lastName, String tenantId) {
        return createUser(
                username,
                email,
                firstName,
                lastName,
                List.of(),
                Map.of("tenant_id", List.of(tenantId)),
                List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"));
    }

    /**
     * Sends Keycloak's "execute actions" email to an existing user - the invite link behind {@link
     * #createUserForInvite}. The message carries a one-time link that completes the given required
     * actions (here: {@code UPDATE_PASSWORD} + {@code VERIFY_EMAIL}) on the realm's own screens, so
     * the inviting admin never sees or relays a password. Requires the realm's SMTP to be working -
     * if it isn't, this throws like every other admin call and the caller decides whether to keep
     * the already-created user (see {@code UserAdminController#create}).
     */
    @Override
    public void sendActionsEmail(String userId, List<String> actions) {
        String token = obtainAdminToken();
        call(
                () ->
                        restClient
                                .put()
                                .uri(
                                        "/admin/realms/{realm}/users/{userId}/execute-actions-email",
                                        realm,
                                        userId)
                                .headers(authHeaders(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(actions)
                                .retrieve()
                                .toBodilessEntity(),
                "send execute-actions email");
    }

    /**
     * Phase 0 Stage 5: the self-service signup path - a permanent (not temporary) password since
     * the user chose it themselves, plus the {@code tenant_id} attribute set at creation time so
     * the new user's very first token already carries it (no separate PUT, no window where the user
     * exists but can't yet be routed to their own schema).
     */
    @Override
    public KeycloakUserRepresentation createUserForSignup(
            String username,
            String email,
            String firstName,
            String lastName,
            String permanentPassword,
            Map<String, String> attributes) {
        Map<String, List<String>> wireAttributes = new HashMap<>();
        attributes.forEach((key, value) -> wireAttributes.put(key, List.of(value)));
        return createUser(
                username,
                email,
                firstName,
                lastName,
                List.of(KeycloakCredentialRepresentation.permanentPassword(permanentPassword)),
                wireAttributes,
                verifyEmailActions());
    }

    /**
     * Sets attributes (e.g. {@code tenant_id}, {@code organization_name}) on an EXISTING user -
     * used when a social-login (Google/etc.) user, who already exists in Keycloak but has no
     * tenant, is onboarded to a freshly created tenant. Requires the realm's
     * unmanagedAttributePolicy to allow tenant_id (see reconcile-keycloak.sh), else Keycloak
     * silently drops it.
     *
     * <p><b>Read-modify-write, not a partial PUT - this method's previous javadoc had the Keycloak
     * semantics exactly backwards, and that mistake broke sign-in for every social-login user this
     * was ever called for.</b> It used to send an attributes-ONLY body on the claim that "email/
     * firstName/lastName are omitted, so Keycloak leaves them unchanged." That is not true of this
     * endpoint since Keycloak 24.0.4: a representation carrying an {@code attributes} map is
     * treated as the user's FULL attribute set, and root attributes absent from the body (email,
     * firstName, lastName) are REMOVED rather than left alone.
     *
     * <p>Live proof in this realm, not a theory: every Google-federated user who went through
     * {@code POST /api/onboarding} (the sole caller, via {@link
     * com.agenticai.core.tenancy.TenantProvisioningService#onboardExistingUser}) had {@code
     * email}/{@code firstName}/{@code lastName} = null afterwards, while every {@code /api/signup}
     * user came out intact - because that path follows its create-user POST with {@link
     * #patchUserFields}, whose PUT re-sends those three root fields alongside the attributes map.
     *
     * <p>The user-visible damage is the second half of the block at {@link #createUser} above,
     * reached from the other direction: blanking those three fields fails the realm's declarative
     * User Profile required-field check (email and both names are required for role "user"), which
     * Keycloak re-evaluates on EVERY authentication, so the user is pinned to the VERIFY_PROFILE
     * "Update Account Information" screen permanently. And because a pending required action makes
     * Keycloak answer the web app's silent {@code prompt=none} re-auth with {@code
     * interaction_required}, the app reads the still-live session as signed-out and drops the user
     * back on the marketing landing page right after they created their org.
     *
     * <p>So: GET the user first, then PUT back its root fields (username, email, firstName,
     * lastName, emailVerified) together with the existing attributes merged with the new ones (new
     * values win on key collision - this is a "set these keys", not "replace everything"). Only
     * non-null root fields are re-asserted; an omitted root field is still left unchanged, it is
     * only the {@code attributes} map that is all-or-nothing. {@code requiredActions} is
     * deliberately never sent - re-asserting it would risk clearing a legitimately pending
     * VERIFY_EMAIL.
     *
     * <p>If the GET fails this throws and no PUT is attempted. That is on purpose: falling back to
     * the old attributes-only PUT would quietly reintroduce exactly the data loss above.
     */
    @Override
    public void setUserAttributes(String userId, Map<String, String> attributes) {
        String token = obtainAdminToken();
        KeycloakUserRepresentation existing = fetchUser(userId, token);

        Map<String, List<String>> merged = new HashMap<>();
        if (existing.attributes() != null) {
            merged.putAll(existing.attributes());
        }
        attributes.forEach((key, value) -> merged.put(key, List.of(value)));

        Map<String, Object> update = new HashMap<>();
        putIfNotNull(update, "username", existing.username());
        putIfNotNull(update, "email", existing.email());
        putIfNotNull(update, "firstName", existing.firstName());
        putIfNotNull(update, "lastName", existing.lastName());
        putIfNotNull(update, "emailVerified", existing.emailVerified());
        update.put("attributes", merged);
        call(
                () ->
                        restClient
                                .put()
                                .uri("/admin/realms/{realm}/users/{userId}", realm, userId)
                                .headers(authHeaders(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(update)
                                .retrieve()
                                .toBodilessEntity(),
                "set user attributes");
    }

    /**
     * Direct feedback: "provide disable option on created user" - suspends (or restores) sign-in
     * for a user without deleting their account, so a tenant-admin can revoke access to an
     * offboarded/compromised account and still restore it later (unlike {@link #deleteUser}, which
     * is permanent). Sends a partial body ({@code {"enabled": ...}} only) - Keycloak's user-update
     * endpoint treats an omitted field as "leave unchanged" rather than requiring the full
     * representation on every PUT, so this doesn't need to fetch-then-resend the user's other
     * fields.
     */
    @Override
    public void setUserEnabled(String userId, boolean enabled) {
        String token = obtainAdminToken();
        call(
                () ->
                        restClient
                                .put()
                                .uri("/admin/realms/{realm}/users/{userId}", realm, userId)
                                .headers(authHeaders(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Map.of("enabled", enabled))
                                .retrieve()
                                .toBodilessEntity(),
                "set user enabled");
    }

    /**
     * Revokes every active Keycloak session of one user ({@code POST
     * /admin/realms/{realm}/users/{id}/logout}). Refresh tokens stop working at once;
     * already-issued access tokens stay valid until their own short expiry - Keycloak has no push
     * revocation for stateless JWTs. Used by the platform-admin session controls ({@code
     * PlatformSessionController}).
     */
    @Override
    public void logoutUser(String userId) {
        String token = obtainAdminToken();
        call(
                () ->
                        restClient
                                .post()
                                .uri("/admin/realms/{realm}/users/{userId}/logout", realm, userId)
                                .headers(authHeaders(token))
                                .retrieve()
                                .toBodilessEntity(),
                "logout user");
    }

    /**
     * Compensating action for {@link com.agenticai.core.tenancy.TenantProvisioningService} - if
     * schema creation/migration fails after the Keycloak user was already created, the user must
     * not be left behind as an orphaned account with no working tenant. Best-effort: failures here
     * are logged by the caller, not retried indefinitely - a rare leftover Keycloak user is a far
     * smaller problem than blocking the compensating cleanup of everything else on a flaky delete.
     */
    @Override
    public void deleteUser(String userId) {
        String token = obtainAdminToken();
        call(
                () ->
                        restClient
                                .delete()
                                .uri("/admin/realms/{realm}/users/{userId}", realm, userId)
                                .headers(authHeaders(token))
                                .retrieve()
                                .toBodilessEntity(),
                "delete user");
    }

    /**
     * Sets a user's realm roles to exactly the given set - adds whatever is missing, removes
     * whatever shouldn't be there anymore. Not an additive-only "grant" call, by design: an admin
     * portal editing "this user's roles" should mean the resulting set matches what was submitted,
     * not accumulate forever.
     *
     * <p><b>Privilege-escalation fix - defence in depth.</b> Every name is checked against {@link
     * TenantAssignableRoles} here, not only in {@code UserAdminController}, so a future caller that
     * forgets the controller-level check still cannot mint a {@code platform-admin} through this
     * client. The only two non-controller callers today ({@code TenantProvisioningService}'s
     * signup/social-onboarding paths) pass {@code Set.of("tenant-admin")}, which is allowlisted -
     * this is a no-op for them.
     */
    @Override
    public void replaceUserRealmRoles(String userId, Set<String> desiredRoleNames) {
        TenantAssignableRoles.requireAllAssignable(desiredRoleNames);
        String token = obtainAdminToken();
        Set<String> current = new HashSet<>(getUserRealmRoleNames(userId));
        Set<String> toAdd = new HashSet<>(desiredRoleNames);
        toAdd.removeAll(current);
        Set<String> toRemove = new HashSet<>(current);
        toRemove.removeAll(desiredRoleNames);
        // Never strip Keycloak's realm default role ("default-roles-<realm>").
        // It is a composite that carries offline_access (refresh tokens for
        // every mobile/IDE/extension client), uma_authorization and the
        // account-console roles, and Keycloak maps it DIRECTLY on each user -
        // so it shows up in `current` like any other role. Before 2026-09-18
        // this method deleted it on every signup (callers pass exactly
        // {tenant-admin}), after which each of those users got "Offline tokens
        // are not allowed for this user" from every app that asks for
        // offline_access. Callers name the roles they manage; the realm
        // default is not one of them.
        toRemove.removeIf(KeycloakAdminClient::isRealmDefaultRole);

        if (!toAdd.isEmpty()) {
            List<KeycloakRoleRepresentation> resolved = resolveRoles(toAdd, token);
            call(
                    () ->
                            restClient
                                    .post()
                                    .uri(
                                            "/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                                            realm,
                                            userId)
                                    .headers(authHeaders(token))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(resolved)
                                    .retrieve()
                                    .toBodilessEntity(),
                    "add user realm roles");
        }
        if (!toRemove.isEmpty()) {
            List<KeycloakRoleRepresentation> resolved = resolveRoles(toRemove, token);
            call(
                    () ->
                            restClient
                                    .method(HttpMethod.DELETE)
                                    .uri(
                                            "/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                                            realm,
                                            userId)
                                    .headers(authHeaders(token))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(resolved)
                                    .retrieve()
                                    .toBodilessEntity(),
                    "remove user realm roles");
        }
    }

    /** Keycloak's per-realm default composite role, e.g. {@code default-roles-agentic-ai}. */
    static boolean isRealmDefaultRole(String roleName) {
        return roleName != null && roleName.startsWith("default-roles-");
    }
}
