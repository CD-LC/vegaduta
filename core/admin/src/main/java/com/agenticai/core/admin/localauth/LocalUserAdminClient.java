package com.agenticai.core.admin.localauth;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakAdminException;
import com.agenticai.core.admin.keycloak.KeycloakUserConflictException;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.admin.keycloak.KeycloakUserValidationException;
import com.agenticai.core.admin.keycloak.TenantAssignableRoles;
import com.agenticai.core.security.publicurl.PublicWebUrl;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@link IdentityUserAdminPort} on the local account table: the third adapter next to Keycloak and
 * Auth0, selected by {@code app.identity.provider=local} (which {@code application-security.yml}
 * derives from {@code app.auth.mode=local}). With it, every caller of the port - signup and
 * onboarding, the tenant user list, invites, role editing, tenant erasure, the platform console -
 * works on a Keycloak-less install without a single change.
 *
 * <p>The exception contract of the port is kept: a duplicate e-mail is a {@link
 * KeycloakUserConflictException}, rejected input a {@link KeycloakUserValidationException}, anything
 * else a {@link KeycloakAdminException}; {@link #replaceUserRealmRoles} still runs the
 * privilege-escalation guard {@link TenantAssignableRoles#requireAllAssignable}.
 *
 * <p>There is no mail server in this mode, so "send the invite e-mail" ({@link #sendActionsEmail})
 * prints the one-time setup link on the server console for the administrator to pass on, exactly
 * like the first-run link.
 */
@Component
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "local")
public class LocalUserAdminClient implements IdentityUserAdminPort {

    private static final Logger log = LoggerFactory.getLogger(LocalUserAdminClient.class);
    private static final String TENANT_ID = "tenant_id";

    private final LocalAccountStore store;
    private final LocalPasswords passwords;
    private final LocalAuthService auth;
    private final LocalSessionService sessions;
    private final String publicWebUrl;

    public LocalUserAdminClient(
            LocalAccountStore store,
            LocalPasswords passwords,
            // Lazy: LocalAuthService is not needed to construct this adapter, only to mint invite links.
            @Lazy LocalAuthService auth,
            @Lazy LocalSessionService sessions,
            @Value("${app.web.public-url:}") String publicWebUrl) {
        this.store = store;
        this.passwords = passwords;
        this.auth = auth;
        this.sessions = sessions;
        this.publicWebUrl = publicWebUrl;
    }

    @Override
    public List<KeycloakUserRepresentation> listUsers() {
        return store.listAll().stream().map(LocalUserAdminClient::toRepresentation).toList();
    }

    @Override
    public List<KeycloakUserRepresentation> listUsersByTenant(String tenantId) {
        return store.listByTenant(tenantId).stream().map(LocalUserAdminClient::toRepresentation).toList();
    }

    @Override
    public Map<String, List<KeycloakUserRepresentation>> listUsersGroupedByTenant() {
        Map<String, List<KeycloakUserRepresentation>> byTenant = new HashMap<>();
        for (LocalAccount account : store.listAll()) {
            if (account.tenantId() != null && !account.tenantId().isBlank()) {
                byTenant.computeIfAbsent(account.tenantId(), k -> new ArrayList<>()).add(toRepresentation(account));
            }
        }
        return byTenant;
    }

    @Override
    public int countUsersByTenant(String tenantId) {
        return store.listByTenant(tenantId).size();
    }

    @Override
    public Optional<KeycloakUserRepresentation> findByEmail(String email) {
        return store.findByEmail(email).map(LocalUserAdminClient::toRepresentation);
    }

    @Override
    public KeycloakUserRepresentation getUserById(String userId) {
        return toRepresentation(require(userId));
    }

    @Override
    public List<String> getUserRealmRoleNames(String userId) {
        return new ArrayList<>(new TreeSet<>(require(userId).roles()));
    }

    @Override
    public KeycloakUserRepresentation createUser(
            String username, String email, String firstName, String lastName, String temporaryPassword, String tenantId) {
        requirePolicy(temporaryPassword, email);
        LocalAccount account =
                newAccount(email, firstName, lastName, passwords.hash(temporaryPassword), tenantId, Map.of());
        // A temporary password must be replaced at first sign-in.
        return insert(account.withPassword(account.passwordHash(), true, false));
    }

    @Override
    public KeycloakUserRepresentation createUserForInvite(
            String username, String email, String firstName, String lastName, String tenantId) {
        // No password yet: the account cannot sign in until the invite link sets one.
        LocalAccount account = newAccount(email, firstName, lastName, null, tenantId, Map.of());
        return insert(account.withPassword(null, false, true));
    }

    @Override
    public void sendActionsEmail(String userId, List<String> actions) {
        LocalAccount account = require(userId);
        String token = auth.issueSetupToken(account);
        String base = PublicWebUrl.normalize(publicWebUrl);
        String link = (base.isEmpty() ? "<your console URL>" : base) + "/login?setup=" + token;
        log.info("Local auth: no mail server in local mode. Give {} this one-time link to set a password"
                + " (single use; expires after app.auth.local.setup-token-ttl, 24 h by default): {}", account.email(), link);
    }

    /**
     * Mints the one-time setup link (replacing any earlier one) and hands it to the caller instead
     * of a log line. Refused once the person has chosen their own password: a fresh setup link
     * would let an administrator take over a teammate's login. (A temporary password the admin set
     * is not theirs yet, so that account can still get a link.) Without {@code app.web.public-url} the link is
     * the path alone and the console puts its own origin in front.
     */
    @Override
    public Optional<String> issueInviteLink(String userId) {
        LocalAccount account = require(userId);
        if (!account.setupPending() && !account.mustChangePassword()) {
            throw new KeycloakUserValidationException(
                    "This person has already set a password. They sign in with it; an invite link"
                            + " is only for someone who has not joined yet.",
                    null);
        }
        String token = auth.issueSetupToken(account);
        return Optional.of(PublicWebUrl.normalize(publicWebUrl) + "/login?setup=" + token);
    }

    @Override
    public KeycloakUserRepresentation createUserForSignup(
            String username,
            String email,
            String firstName,
            String lastName,
            String permanentPassword,
            Map<String, String> attributes) {
        requirePolicy(permanentPassword, email);
        String tenantId = attributes == null ? null : attributes.get(TENANT_ID);
        Map<String, List<String>> extra = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach(
                    (key, value) -> {
                        if (!TENANT_ID.equals(key) && value != null) {
                            extra.put(key, List.of(value));
                        }
                    });
        }
        LocalAccount account = newAccount(email, firstName, lastName, passwords.hash(permanentPassword), tenantId, extra);
        if (LocalFirstRun.ownerSignupActive()) {
            // First-run owner: refuse sign-in until the setup link sets the real password.
            account = account.withPassword(account.passwordHash(), false, true);
        }
        return insert(account);
    }

    @Override
    public void setUserAttributes(String userId, Map<String, String> attributes) {
        LocalAccount account = require(userId);
        Map<String, List<String>> merged = new LinkedHashMap<>(account.attributes());
        String tenantId = account.tenantId();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (TENANT_ID.equals(entry.getKey())) {
                tenantId = entry.getValue();
            } else if (entry.getValue() == null) {
                merged.remove(entry.getKey());
            } else {
                merged.put(entry.getKey(), List.of(entry.getValue()));
            }
        }
        store.update(account.withTenant(tenantId).withAttributes(merged));
    }

    @Override
    public void setUserEnabled(String userId, boolean enabled) {
        LocalAccount account = require(userId);
        store.update(account.withEnabled(enabled));
        if (!enabled) {
            sessions.revokeAll(userId);
        }
    }

    @Override
    public void logoutUser(String userId) {
        require(userId);
        sessions.revokeAll(userId);
    }

    @Override
    public void deleteUser(String userId) {
        store.delete(parseId(userId));
    }

    @Override
    public void replaceUserRealmRoles(String userId, Set<String> desiredRoleNames) {
        TenantAssignableRoles.requireAllAssignable(desiredRoleNames);
        LocalAccount account = require(userId);
        store.update(account.withRoles(desiredRoleNames));
    }

    // ------------------------------------------------------------------ helpers

    private LocalAccount require(String userId) {
        return store.findById(parseId(userId))
                .orElseThrow(() -> new KeycloakAdminException("No such user: " + userId, null));
    }

    private static String parseId(String userId) {
        try {
            return UUID.fromString(userId).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new KeycloakAdminException("No such user: " + userId, e);
        }
    }

    private static LocalAccount newAccount(
            String email,
            String firstName,
            String lastName,
            String passwordHash,
            String tenantId,
            Map<String, List<String>> attributes) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new KeycloakUserValidationException("A valid e-mail address is required.", null);
        }
        return new LocalAccount(
                UUID.randomUUID().toString(),
                email.trim(),
                firstName,
                lastName,
                passwordHash,
                false,
                true,
                false,
                tenantId,
                Set.of(),
                attributes,
                Instant.now(),
                null);
    }

    private KeycloakUserRepresentation insert(LocalAccount account) {
        try {
            store.insert(account);
        } catch (LocalAccountStore.DuplicateAccountException e) {
            throw new KeycloakUserConflictException("An account with e-mail '" + account.email() + "' already exists.", e);
        }
        return toRepresentation(account);
    }

    private static void requirePolicy(String password, String email) {
        String violation = LocalPasswords.violation(password, email);
        if (violation != null) {
            throw new KeycloakUserValidationException(violation, null);
        }
    }

    static KeycloakUserRepresentation toRepresentation(LocalAccount a) {
        Map<String, List<String>> attributes = new LinkedHashMap<>(a.attributes());
        if (a.tenantId() != null && !a.tenantId().isBlank()) {
            attributes.put(TENANT_ID, List.of(a.tenantId()));
        }
        List<String> required = a.mustChangePassword() || a.setupPending() ? List.of("UPDATE_PASSWORD") : List.of();
        return new KeycloakUserRepresentation(
                a.id(),
                a.email(),
                a.email(),
                a.firstName(),
                a.lastName(),
                a.enabled(),
                Boolean.TRUE,
                null,
                attributes,
                required,
                a.createdAt() == null ? null : a.createdAt().toEpochMilli());
    }
}
