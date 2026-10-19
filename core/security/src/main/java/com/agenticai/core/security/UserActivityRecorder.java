package com.agenticai.core.security;

/**
 * "This person just made an authenticated request" - the seam {@link TenantContextFilter} calls so
 * the platform can show last-activity without {@code core/security} depending on {@code
 * core/tenancy} (same dependency-inversion shape as {@link TenantStatusGuard}; the real
 * implementation, {@code AppUserLastSeenRecorder}, lives in core/tenancy).
 *
 * <p>Contract: implementations are called on EVERY authenticated request, so they must be cheap,
 * must throttle their own writes, and must never throw or block - the filter still guards the call
 * with a catch-all, but an implementation that needs that guard is a bug.
 */
public interface UserActivityRecorder {

    /**
     * @param tenantId the JWT {@code tenant_id} claim (schema name), never blank here
     * @param subject the JWT subject, i.e. the Keycloak user id
     */
    void recordSeen(String tenantId, String subject);
}
