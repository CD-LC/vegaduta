package com.agenticai.core.agents.appbuilder;

import com.agenticai.core.security.TenantContextFilter;
import com.agenticai.core.security.TenantStatusGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Shared service-to-service gate for the {@code /api/internal/app-builder/**} controllers:
 * the {@code X-Service-Token} shared-secret check (constant-time, fail closed when unconfigured)
 * plus the checks that bound the caller-chosen {@code X-Tenant-Id} claim.
 *
 * <p>The token is a single static credential not bound to any tenant, so the claim needs its own
 * enforcement: a reserved/system schema is never a tenant (mirrors {@code TenantContextFilter}),
 * {@code app.app-builder.allowed-tenants} (empty = unrestricted, logged at boot) constrains which
 * tenant schemas the token may act for, and {@link TenantStatusGuard} refuses claims naming a
 * tenant that does not exist or was stopped/suspended.
 */
final class AppBuilderServiceAuth {

    private static final Logger log = LoggerFactory.getLogger(AppBuilderServiceAuth.class);

    private final String serviceToken;
    private final Set<String> allowedTenants;
    private final ObjectProvider<TenantStatusGuard> tenantStatusGuard;

    AppBuilderServiceAuth(
            String serviceToken,
            String allowedTenantsRaw,
            ObjectProvider<TenantStatusGuard> tenantStatusGuard) {
        this.serviceToken = serviceToken;
        this.tenantStatusGuard = tenantStatusGuard;
        this.allowedTenants = parseAllowedTenants(allowedTenantsRaw);
        if (this.allowedTenants.isEmpty()) {
            log.warn(
                "app.app-builder.allowed-tenants is empty - the X-Service-Token credential can drive"
                    + " generation for ANY tenant id presented in X-Tenant-Id. Set"
                    + " APP_BUILDER_ALLOWED_TENANTS to the tenant schemas app-builder may act for.");
        }
    }

    /** Constant-time shared-secret check. Missing/blank configured token fails closed. */
    void authenticate(String presentedToken) {
        if (serviceToken == null || serviceToken.isBlank()) {
            // Fail closed: no configured secret means no caller can ever be trusted.
            throw new AppBuilderTokenMissingException(
                    "App Builder service token is not configured on this server; endpoint"
                        + " disabled.");
        }
        if (presentedToken == null || presentedToken.isBlank()) {
            throw new AppBuilderTokenMissingException("Missing X-Service-Token header.");
        }
        boolean matches =
                MessageDigest.isEqual(
                        serviceToken.getBytes(StandardCharsets.UTF_8),
                        presentedToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new AppBuilderTokenInvalidException("Invalid X-Service-Token.");
        }
    }

    /**
     * Bounds the caller-chosen tenant claim before any context is set: a reserved/system schema is
     * never a tenant, and when {@code app.app-builder.allowed-tenants} is configured the claimed
     * tenant must be listed.
     */
    void authorizeTenantClaim(String tenantId) {
        if (TenantContextFilter.isSystemSchemaClaim(tenantId)) {
            throw new AppBuilderTokenInvalidException("X-Tenant-Id names a reserved schema, not a tenant.");
        }
        if (!allowedTenants.isEmpty() && !allowedTenants.contains(tenantId.trim())) {
            throw new AppBuilderTokenInvalidException(
                    "X-Tenant-Id is not in this server's app-builder tenant allow-list.");
        }
    }

    /**
     * Runs INSIDE the claimed tenant's context (the guard's own lookups are control-plane
     * qualified, same contract {@code TenantContextFilter} relies on): the service token cannot
     * mint work for a tenant that does not exist or was stopped/suspended.
     */
    void requireUsableTenant(String tenantId) {
        TenantStatusGuard guard = tenantStatusGuard.getIfAvailable();
        if (guard != null && !guard.isUsable(tenantId)) {
            throw new AppBuilderTokenInvalidException(
                    "X-Tenant-Id names a tenant that does not exist or is not usable on this deployment.");
        }
    }

    private static Set<String> parseAllowedTenants(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(parsed);
    }
}
