package com.agenticai.core.templates;

import com.agenticai.core.security.TenantContext;
import java.util.Optional;

/**
 * Caller identity and role checks for {@link CommunityTemplateService}. Every refusal is a {@link
 * TemplateNotFoundException} (404, anti-enumeration), never a 403.
 */
final class TemplateAccess {

    // SEC-010: MODERATION of the cross-tenant gallery (approve/reject/withdraw/
    // verify/unverify, the pending queue, the approved list with submitter
    // metadata) is platform-admin ONLY. PERM_MANAGE_TEMPLATES is a TENANT
    // permission - every tenant admin holds it (DefaultPermissionBundles gives
    // TENANT_ADMIN every permission) - so it only governs tenant-level template
    // work (snapshotting a tenant workflow someone else in the tenant created).
    private static final String PERM_MANAGE_TEMPLATES = "PERM_MANAGE_TEMPLATES";
    private static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";

    private TemplateAccess() {
    }

    /**
     * @return the platform admin's subject. Throws {@link TemplateNotFoundException} (404,
     *     anti-enumeration) for anyone else - including a tenant admin holding every tenant
     *     permission (SEC-010).
     */
    static String requireAdmin() {
        Optional<TenantContext> ctx = TenantContext.current();
        if (ctx.isEmpty() || !isPlatformAdmin()) {
            // The moderation surface stays invisible to non-admins: a request looks the same as one
            // for a made-up route.
            throw new TemplateNotFoundException();
        }
        return ctx.get().getSubject();
    }

    /** Tenant-level template management (own tenant only) - NOT moderation. */
    static boolean isAdmin() {
        return TenantContext.current()
                .map(ctx -> ctx.hasRole(PERM_MANAGE_TEMPLATES) || ctx.hasRole(ROLE_PLATFORM_ADMIN))
                .orElse(false);
    }

    /** SEC-010: the only authority allowed to moderate the shared, cross-tenant gallery. */
    private static boolean isPlatformAdmin() {
        return TenantContext.current().map(ctx -> ctx.hasRole(ROLE_PLATFORM_ADMIN)).orElse(false);
    }

    static String requireSubject() {
        return TenantContext.current()
                .map(TenantContext::getSubject)
                .filter(s -> s != null && !s.isBlank())
                .orElseThrow(TemplateNotFoundException::new);
    }
}
