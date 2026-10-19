package com.agenticai.core.providers.addons;

import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Who may change the add-on set. Reading it needs only a signed-in user (the console shows which
 * features are on to everyone who can use them).
 *
 * <p>An add-on is a container or a model on the machine the install runs on, so it is install-wide,
 * not per tenant. On a one-owner install ({@code app.auth.mode=local}, the personal profile) the
 * owner is that tenant's admin and may change it. On a shared install a tenant admin runs one
 * organisation, not the machine, so only a platform admin may; set
 * {@code app.addons.tenant-admin-may-change=true} to let tenant admins do it on a self-hosted
 * install where they own the box.
 */
@Component("addonAccess")
public class AddonAccess {

    static final String TENANT_ADMIN = "ROLE_tenant-admin";
    static final String PLATFORM_ADMIN = "ROLE_platform-admin";

    private final boolean tenantAdminMayChange;

    public AddonAccess(Environment environment) {
        Boolean explicit = environment.getProperty("app.addons.tenant-admin-may-change", Boolean.class);
        this.tenantAdminMayChange = explicit != null
                ? explicit
                : "local".equalsIgnoreCase(environment.getProperty("app.auth.mode", "keycloak"));
    }

    /** Used by {@code @PreAuthorize} on every handler that changes the add-on set. */
    public boolean canChange(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        boolean platformAdmin = false;
        boolean tenantAdmin = false;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            platformAdmin |= PLATFORM_ADMIN.equals(authority.getAuthority());
            tenantAdmin |= TENANT_ADMIN.equals(authority.getAuthority());
        }
        return platformAdmin || (tenantAdmin && tenantAdminMayChange);
    }
}
