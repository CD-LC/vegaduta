package com.agenticai.core.platform;

import java.util.UUID;

/** Thrown by {@link PlatformTenantPlanService} for an unknown tenant id. Maps to HTTP 404. */
public class PlatformTenantNotFoundException extends RuntimeException {

    public PlatformTenantNotFoundException(UUID tenantId) {
        super("No tenant with id " + tenantId);
    }
}
