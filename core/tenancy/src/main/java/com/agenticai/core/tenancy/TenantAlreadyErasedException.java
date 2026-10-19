package com.agenticai.core.tenancy;

import java.util.UUID;

/**
 * Platform-admin "delete tenant completely" control - the target tenant's
 * {@link TenantStatus} is already {@link TenantStatus#ERASED}. Mapped to 409
 * by {@code GlobalExceptionHandler}: the request is well-formed and the
 * tenant id is real, but its current state conflicts with running the
 * deletion a second time (there is nothing left to delete, and re-running
 * every step - Keycloak, S3, Redis, the two other services - would be
 * wasted, best-effort-swallowed no-ops that could mislead the audit log into
 * recording a second "COMPLETED" for work that never happened).
 */
public class TenantAlreadyErasedException extends RuntimeException {

    public TenantAlreadyErasedException(UUID tenantId) {
        super("Tenant " + tenantId + " has already been erased.");
    }
}
