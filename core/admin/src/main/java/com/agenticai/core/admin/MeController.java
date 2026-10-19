package com.agenticai.core.admin;

import com.agenticai.core.admin.permission.AdminPermissionAuthoritySource;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/me} - the caller's own identity and effective access, for
 * the web console's permission-aware UI (granular-RBAC pass, 2026-09-23).
 *
 * <p>Before this, the console only knew what the JWT itself carries
 * (realm roles), and granular permissions lived purely server-side - a
 * scoped user would have seen nav items that all 403'd. This endpoint is
 * the read model the UI gates against: same inputs as the JWT converter's
 * own lookup ({@code TenantContext} roles + {@link
 * AdminPermissionAuthoritySource}), so what it reports is exactly what
 * {@code SecurityConfig} will enforce on the next call - the two never
 * disagree by construction.
 *
 * <p>Falls through to {@code anyRequest().authenticated()} in {@code
 * SecurityConfig}: any authenticated member may read their OWN access set
 * (it is their own token's data, nothing tenant-wide), and the endpoint
 * deliberately returns an empty permission list for callers with no grants
 * rather than an error. Tenant-less platform admins get roles + empty
 * permissions - their gate is {@code ROLE_platform-admin}, not this catalog.
 */
@RestController
@RequestMapping("/api/me")
@Profile("security")
public class MeController {

    private static final String ROLE_PREFIX = "ROLE_";

    private final AdminPermissionAuthoritySource permissionAuthoritySource;

    public MeController(AdminPermissionAuthoritySource permissionAuthoritySource) {
        this.permissionAuthoritySource = permissionAuthoritySource;
    }

    public record MeResponse(
        String subject,
        String tenantId,
        List<String> roles,
        List<String> permissions
    ) {
    }

    @GetMapping
    public MeResponse me() {
        TenantContext context = TenantContext.current().orElseThrow(() -> new IllegalStateException(
            "No TenantContext for an authenticated /api/me request - SecurityConfig should have blocked this"));
        List<String> roleNames = context.getRoles().stream()
            .filter(role -> role.startsWith(ROLE_PREFIX))
            .map(role -> role.substring(ROLE_PREFIX.length()))
            .sorted()
            .toList();
        List<String> permissions = List.copyOf(
            permissionAuthoritySource.effectivePermissions(
                context.getTenantId(), context.getSubject(), Set.copyOf(roleNames)))
            .stream().sorted().toList();
        return new MeResponse(context.getSubject(), context.getTenantId(), roleNames, permissions);
    }
}
