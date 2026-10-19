package com.agenticai.core.admin.permission;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The server-side allowlist of permission names a tenant-admin is permitted
 * to grant through {@code /api/admin/users/**} - structural mirror of {@code
 * TenantAssignableRoles}, same reject-not-filter posture for the same
 * reason: a silent filter would turn a bad request into a 200 with a
 * misleading audit entry.
 *
 * <p>Unlike {@code TenantAssignableRoles}, there is no excluded entry here -
 * every {@link Permission} constant is tenant-scoped by construction (the
 * catalog has no cross-tenant/platform-operator equivalent of {@code
 * platform-admin}), so {@link #ASSIGNABLE} is simply every catalog name.
 */
public final class TenantAssignablePermissions {

    public static final Set<String> ASSIGNABLE =
        Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private TenantAssignablePermissions() {
    }

    public static boolean isAssignable(String permissionName) {
        return permissionName != null && ASSIGNABLE.contains(permissionName);
    }

    /**
     * Throws {@link IllegalArgumentException} (mapped to HTTP 400 by
     * {@code GlobalExceptionHandler}) naming every submitted permission that
     * is not in the catalog. A null/blank entry is rejected the same way an
     * unknown name is.
     *
     * @param permissionNames the caller-supplied set; {@code null} or empty
     *                        is allowed (clearing a user's permissions is a
     *                        legitimate de-escalation, not an escalation)
     */
    public static void requireAllAssignable(Collection<String> permissionNames) {
        if (permissionNames == null || permissionNames.isEmpty()) {
            return;
        }
        Set<String> rejected = new LinkedHashSet<>();
        for (String permissionName : permissionNames) {
            if (!isAssignable(permissionName)) {
                rejected.add(permissionName == null ? "<null>" : permissionName);
            }
        }
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(
                "Not a recognized permission: " + rejected + ". Assignable permissions are " + sortedAssignable() + "."
            );
        }
    }

    private static java.util.List<String> sortedAssignable() {
        return ASSIGNABLE.stream().sorted().toList();
    }
}
