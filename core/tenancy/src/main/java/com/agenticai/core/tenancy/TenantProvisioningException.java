package com.agenticai.core.tenancy;

/**
 * A signup partially failed after at least one side effect (Keycloak user
 * and/or Postgres schema) was attempted. By the time this is thrown, {@link
 * TenantProvisioningService} has already run its compensating cleanup and
 * marked the {@link Tenant} row {@link TenantStatus#PROVISIONING_FAILED} -
 * this exception exists purely to report the failure to the caller, not to
 * signal that any cleanup is still owed.
 */
public class TenantProvisioningException extends RuntimeException {

    public TenantProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
