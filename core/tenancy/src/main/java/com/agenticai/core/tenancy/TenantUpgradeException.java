package com.agenticai.core.tenancy;

/**
 * #811 - a self-serve upgrade request that can never succeed as asked:
 * targeting {@code FREE} (that's a downgrade, not something this flow
 * handles - see {@code TenantUpgradeService#initiateUpgrade}), or targeting
 * the plan the tenant is already on. Both are client mistakes, not server
 * bugs - mapped to 400 by {@code GlobalExceptionHandler}, same posture as
 * {@link com.agenticai.core.security.TenantLimitExceededException}'s
 * sibling handlers.
 */
public class TenantUpgradeException extends RuntimeException {

    public TenantUpgradeException(String message) {
        super(message);
    }
}
