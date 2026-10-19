package com.agenticai.core.admin.keycloak;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The server-side allowlist of realm roles a <b>tenant-admin</b> is permitted
 * to assign through {@code /api/admin/users/**}.
 *
 * <p><b>Why this exists (privilege-escalation fix):</b> before this class,
 * {@code UserAdminController#setRoles} passed the request body's
 * {@code roles} set straight into
 * {@link KeycloakAdminClient#replaceUserRealmRoles} with no validation at
 * all, and {@code SetUserRolesRequest} only carried {@code @NotNull}. The
 * only other guard, {@code UserAdminController#requireInTenant}, checks that
 * the <i>target</i> user shares the caller's {@code tenant_id} - which a
 * caller targeting their own user id trivially satisfies. Since self-service
 * signup grants {@code tenant-admin}
 * ({@code TenantProvisioningService}), any signed-up user could
 * {@code PUT /api/admin/users/{ownId}/roles {"roles":["platform-admin"]}},
 * re-authenticate, and hold the cross-tenant {@code platform-admin} role
 * that gates {@code /api/platform/**} (plan changes, the emergency kill
 * switch, the hosted-LLM provider keys). This class is the missing check.
 *
 * <p><b>The allowlist and its evidence.</b>
 * {@code deploy/keycloak/agentic-ai-realm.json} defines exactly five realm
 * roles. Four of them are tenant-scoped and safe for a tenant-admin to hand
 * out inside their own tenant:
 * <ul>
 *   <li>{@code tenant-admin} - "Full admin over their own tenant"</li>
 *   <li>{@code agent-builder} - can build agents, no billing/user management</li>
 *   <li>{@code delegated-user} - assisted/delegated-setup role</li>
 *   <li>{@code end-customer} - widget public-auth path, "no access to any
 *       admin endpoint"</li>
 * </ul>
 * The fifth, {@code platform-admin}, is deliberately excluded - the realm
 * JSON's own description for it says it is a "Cross-tenant platform
 * operator... Not auto-granted at signup like tenant-admin; must be assigned
 * manually in the Keycloak admin console to real platform operator accounts
 * only." That is precisely the sentence this class enforces in code. The
 * four allowed names are also exactly the set the admin UI already offers
 * ({@code web/app/components/AdminUsersPanel.tsx}'s {@code REALM_ROLES}), so
 * this rejects nothing a legitimate console user can currently submit.
 *
 * <p><b>Reject, never silently filter.</b> {@link #requireAllAssignable}
 * throws rather than quietly dropping the offending names. A silent filter
 * would turn a privilege-escalation attempt into a {@code 200 OK} with a
 * successful-looking audit entry, hiding the attack from exactly the log an
 * operator would go read afterwards.
 *
 * <p>Matching is exact and case-sensitive because Keycloak realm role names
 * are. {@code "Platform-Admin"} or {@code "ROLE_platform-admin"} are not in
 * the set, so they are rejected here and would in any case fail to resolve
 * against Keycloak.
 */
public final class TenantAssignableRoles {

    public static final String TENANT_ADMIN = "tenant-admin";
    public static final String AGENT_BUILDER = "agent-builder";
    public static final String DELEGATED_USER = "delegated-user";
    public static final String END_CUSTOMER = "end-customer";

    /** Never assignable through the tenant admin portal - see this class's javadoc. */
    public static final String PLATFORM_ADMIN = "platform-admin";

    public static final Set<String> ASSIGNABLE =
        Set.of(TENANT_ADMIN, AGENT_BUILDER, DELEGATED_USER, END_CUSTOMER);

    private TenantAssignableRoles() {
    }

    public static boolean isAssignable(String roleName) {
        return roleName != null && ASSIGNABLE.contains(roleName);
    }

    /**
     * Throws {@link IllegalArgumentException} (mapped to HTTP 400 by
     * {@code GlobalExceptionHandler}) naming every submitted role that is not
     * tenant-assignable. A null/blank entry is rejected the same way an
     * unknown name is - it can only ever be a malformed request, and letting
     * it through would reach Keycloak's role-resolution call as a garbage
     * lookup.
     *
     * @param roleNames the caller-supplied set; {@code null} or empty is
     *                  allowed (clearing a user's roles is a legitimate
     *                  de-escalation, not an escalation)
     */
    public static void requireAllAssignable(Collection<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return;
        }
        Set<String> rejected = new LinkedHashSet<>();
        for (String roleName : roleNames) {
            if (!isAssignable(roleName)) {
                rejected.add(roleName == null ? "<null>" : roleName);
            }
        }
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(
                "Not assignable through the tenant admin portal: " + rejected
                    + ". Assignable roles are " + sortedAssignable()
                    + ". (" + PLATFORM_ADMIN + " is a cross-tenant platform-operator role and is granted"
                    + " only in the Keycloak admin console, never through this API.)"
            );
        }
    }

    private static java.util.List<String> sortedAssignable() {
        return ASSIGNABLE.stream().sorted().toList();
    }
}
