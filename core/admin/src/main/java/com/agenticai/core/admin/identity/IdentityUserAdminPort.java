package com.agenticai.core.admin.identity;

import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * User, realm-role and account-email administration against the identity provider.
 *
 * <p>This is the seam between the platform and whichever identity provider holds the user
 * directory. Platform code (tenancy, sharing, platform console, admin controllers) depends on this
 * port, never on a provider client. Two implementations exist, selected by {@code
 * app.identity.provider}: {@code KeycloakAdminClient} (the default) and {@code
 * Auth0UserAdminClient} ({@code auth0}); see section 7, "Add an identity provider adapter", in
 * {@code docs/extending.md}.
 *
 * <p>The user model is still {@link KeycloakUserRepresentation} (id, username, email, names,
 * enabled flag, attributes). It is a plain record with no Keycloak dependency; a second adapter
 * maps its own representation onto it. Error contracts are the {@code Keycloak*Exception} types the
 * callers already handle.
 */
public interface IdentityUserAdminPort {

    List<KeycloakUserRepresentation> listUsers();

    /** Users whose {@code tenant_id} attribute equals {@code tenantId}. */
    List<KeycloakUserRepresentation> listUsersByTenant(String tenantId);

    /**
     * Every user grouped by their {@code tenant_id} attribute (the tenant schema name) from a
     * single realm read, for callers that need per-tenant figures for many tenants at once. Users
     * without a {@code tenant_id} are omitted.
     */
    Map<String, List<KeycloakUserRepresentation>> listUsersGroupedByTenant();

    int countUsersByTenant(String tenantId);

    Optional<KeycloakUserRepresentation> findByEmail(String email);

    KeycloakUserRepresentation getUserById(String userId);

    List<String> getUserRealmRoleNames(String userId);

    KeycloakUserRepresentation createUser(
            String username,
            String email,
            String firstName,
            String lastName,
            String temporaryPassword,
            String tenantId);

    KeycloakUserRepresentation createUserForInvite(
            String username, String email, String firstName, String lastName, String tenantId);

    /** Asks the provider to email the user the given required actions (e.g. UPDATE_PASSWORD). */
    void sendActionsEmail(String userId, List<String> actions);

    /**
     * For a provider with no mail server: a one-time link the invited user opens to set their
     * password, for the administrator to pass on (chat, message, in person). Only for an account
     * that has not set a password yet. Empty when the provider delivers invites itself by e-mail
     * ({@link #sendActionsEmail}), which is every provider except local auth.
     */
    default Optional<String> issueInviteLink(String userId) {
        return Optional.empty();
    }

    KeycloakUserRepresentation createUserForSignup(
            String username,
            String email,
            String firstName,
            String lastName,
            String permanentPassword,
            Map<String, String> attributes);

    void setUserAttributes(String userId, Map<String, String> attributes);

    void setUserEnabled(String userId, boolean enabled);

    /** Ends every active session of the user at the identity provider (forces a fresh sign-in). */
    void logoutUser(String userId);

    void deleteUser(String userId);

    /** Replaces the user's assignable realm roles with exactly {@code desiredRoleNames}. */
    void replaceUserRealmRoles(String userId, Set<String> desiredRoleNames);
}
