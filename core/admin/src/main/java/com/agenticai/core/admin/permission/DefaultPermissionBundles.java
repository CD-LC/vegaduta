package com.agenticai.core.admin.permission;

import com.agenticai.core.admin.keycloak.TenantAssignableRoles;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The default {@link Permission} bundle seeded for a user when a role is
 * assigned (at user creation, and again whenever {@code setRoles} changes
 * their roles) - see {@code UserPermissionService#seedMissingDefaultPermissions}.
 * Gives a tenant admin a sane starting point without forcing them to
 * hand-pick every permission for every user; after seeding, permissions are
 * freely editable per user, independent of role.
 *
 * <p>{@code tenant-admin} maps to every catalog permission. Since
 * 2026-09-23 these seeded grants are the REAL authorization set (see {@link
 * Permission}'s javadoc) - a tenant-admin keeps full access only as long as
 * their stored rows keep them, so trimming another admin's grants genuinely
 * trims their access. The bundle just decides what a newly-minted admin
 * starts with.
 *
 * <p>{@code agent-builder} reflects {@code TenantAssignableRoles}'s own
 * description of that role ("can build agents, no billing/user
 * management"), widened 2026-09-23 to the builder-facing admin scopes
 * (workflows, integrations, agent autonomy, tenant-authored tools) so a
 * builder can operate the surfaces their role already lets them reach.
 * {@code delegated-user} and {@code end-customer} default to no
 * permissions - least privilege by default, matching the admin console's
 * existing "least privilege by default" footer text.
 */
public final class DefaultPermissionBundles {

    private static final Map<String, Set<Permission>> BY_ROLE = Map.of(
        TenantAssignableRoles.TENANT_ADMIN, EnumSet.allOf(Permission.class),
        TenantAssignableRoles.AGENT_BUILDER, EnumSet.of(
            Permission.MANAGE_WORKFLOWS_ADMIN,
            Permission.MANAGE_INTEGRATIONS,
            Permission.MANAGE_AGENTS,
            Permission.MANAGE_TOOLS
        ),
        TenantAssignableRoles.DELEGATED_USER, EnumSet.noneOf(Permission.class),
        TenantAssignableRoles.END_CUSTOMER, EnumSet.noneOf(Permission.class)
    );

    private DefaultPermissionBundles() {
    }

    /**
     * The union of default permission names for a set of role names.
     * Unknown or {@code null} role names contribute nothing and never
     * throw - this runs after roles are already validated against {@code
     * TenantAssignableRoles}, so an unrecognized name here is not this
     * method's concern to reject.
     */
    public static Set<String> unionForRoles(Collection<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return Set.of();
        }
        Set<String> union = new LinkedHashSet<>();
        for (String roleName : roleNames) {
            Set<Permission> bundle = BY_ROLE.get(roleName);
            if (bundle != null) {
                union.addAll(bundle.stream().map(Enum::name).collect(Collectors.toSet()));
            }
        }
        return union;
    }
}
