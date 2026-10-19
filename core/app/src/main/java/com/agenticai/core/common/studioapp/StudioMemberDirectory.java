package com.agenticai.core.common.studioapp;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Resolves tenant members for studio collaboration. Same pattern as the sharing module's {@code
 * ShareController}: an email is looked up in Keycloak and accepted only if the user carries the
 * caller's own {@code tenant_id} attribute, so a project can never be shared across tenants.
 * Keycloak exists only under the {@code security} profile, hence the {@link ObjectProvider}.
 */
@Component
public class StudioMemberDirectory {

    /** A resolved tenant member: Keycloak subject plus display data. */
    public record Person(String subject, String email, String name) {}

    private final ObjectProvider<IdentityUserAdminPort> keycloak;

    public StudioMemberDirectory(ObjectProvider<IdentityUserAdminPort> keycloak) {
        this.keycloak = keycloak;
    }

    /** True when a Keycloak-backed directory is configured (false in non-security profiles). */
    public boolean available() {
        return keycloak.getIfAvailable() != null;
    }

    /** The tenant member with this email, or empty when unknown OR in another tenant. */
    public Optional<Person> findInCallerTenant(String email) {
        IdentityUserAdminPort client = keycloak.getIfAvailable();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (client == null || tenantId == null) {
            return Optional.empty();
        }
        return client.findByEmail(email)
                .filter(
                        u ->
                                u.id() != null
                                        && u.attributes() != null
                                        && u.attributes()
                                                .getOrDefault("tenant_id", List.of())
                                                .contains(tenantId))
                .map(StudioMemberDirectory::person);
    }

    /** Best-effort display data for a subject; never throws. Falls back to a bare Person. */
    public Person describe(String subject) {
        IdentityUserAdminPort client = keycloak.getIfAvailable();
        if (client != null) {
            try {
                return person(client.getUserById(subject));
            } catch (RuntimeException ignored) {
                // user removed or Keycloak unreachable - show a neutral label
            }
        }
        return new Person(subject, null, "Member");
    }

    private static Person person(KeycloakUserRepresentation u) {
        String full =
                ((u.firstName() == null ? "" : u.firstName())
                                + " "
                                + (u.lastName() == null ? "" : u.lastName()))
                        .strip();
        String name =
                !full.isEmpty()
                        ? full
                        : u.username() != null && !u.username().isBlank()
                                ? u.username()
                                : u.email() != null ? u.email() : "Member";
        return new Person(u.id(), u.email(), StudioProjectRules.clip(name, 160));
    }
}
