package com.agenticai.core.security;

/**
 * Dependency-inversion seam for {@link TenantContextFilter}: {@code
 * core/security} is the lowest-layer module ({@code core/tenancy} already
 * depends on it, via {@link TenantContext}), so the filter can't reach
 * {@code TenantRepository} directly without creating a module cycle. This
 * interface lives here instead; {@code core/tenancy} provides the real
 * {@code @Component} implementation (backed by {@code TenantRepository}),
 * and {@link com.agenticai.core.security.SecurityConfig} wires it into
 * {@link TenantContextFilter} as an {@code Optional} bean - present in the
 * full app (where tenancy is always on the classpath), absent in any
 * narrower test context that only pulls in {@code core/security}.
 *
 * <p>Closes a real gap found 2026-07-11: a signup that failed after
 * {@code TenantProvisioningService} created the Keycloak user but before
 * (or during) rollback left an orphaned, still-loginable Keycloak account
 * tagged with a {@code tenant_id} whose Postgres schema had already been
 * dropped by {@link com.agenticai.core.tenancy.TenantProvisioningService
 * #compensate}. Every request from that account then hit a raw {@code
 * relation "..." does not exist} 500 from Hibernate - once per panel, on
 * every page load - instead of one clear, actionable error. See {@code
 * TenantProvisioningService#compensate}'s own javadoc: a cleanup failure
 * there (e.g. {@code keycloakAdminClient.deleteUser} throwing) is logged
 * but deliberately not rethrown, so an orphaned Keycloak user is a known,
 * accepted residual risk on the provisioning side - this guard is what
 * keeps that residual risk from turning into a wall of stack traces for
 * whoever happens to still be able to log into it.
 */
public interface TenantStatusGuard {

    /**
     * @param tenantId the raw {@code tenant_id} JWT claim (the tenant's
     *     schema name, before {@code SchemaNameSanitizer} would run) - never
     *     null when called, since {@link TenantContextFilter} only invokes
     *     this after finding a non-blank claim.
     * @return true if requests for this tenant should proceed as normal.
     *     Deliberately also true when {@code tenantId} does not match any
     *     known {@code Tenant} row at all (rather than false) - this guard
     *     only ever blocks a tenant it can positively confirm is unusable;
     *     an unrecognized id is left to whatever downstream behavior already
     *     existed before this guard was added (e.g. falling through to the
     *     default schema), not a new failure mode of its own.
     */
    boolean isUsable(String tenantId);
}
