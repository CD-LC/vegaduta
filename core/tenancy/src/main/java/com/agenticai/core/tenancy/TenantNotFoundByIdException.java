package com.agenticai.core.tenancy;

import java.util.UUID;

/**
 * Platform-admin "delete tenant completely" control - no {@link Tenant} row
 * with this id. Mapped to 404 by {@code GlobalExceptionHandler}.
 *
 * <p>Deliberately its own type rather than reusing {@code
 * com.agenticai.core.platform.PlatformTenantNotFoundException} (same
 * shape/status, different feature) - {@code core/tenancy} cannot depend on
 * {@code core/platform}, which already depends the other way, on {@code
 * core/tenancy}.
 */
public class TenantNotFoundByIdException extends RuntimeException {

    public TenantNotFoundByIdException(UUID tenantId) {
        super("No tenant with id " + tenantId);
    }
}
