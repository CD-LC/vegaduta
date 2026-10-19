package com.agenticai.core.tenancy;

/**
 * Thrown by {@link TenantProvisioningService#signup} before any provisioning
 * side effect has happened (org name or admin email already registered) -
 * always a clean, no-cleanup-needed rejection, never wrapped by {@link
 * TenantProvisioningException}'s compensating-rollback path.
 */
public class DuplicateTenantException extends RuntimeException {

    public DuplicateTenantException(String message) {
        super(message);
    }
}
