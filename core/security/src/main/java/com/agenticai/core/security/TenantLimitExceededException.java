package com.agenticai.core.security;

/**
 * Thrown when an action would push a tenant past a plan-enforced ceiling
 * (team-member seats or total agents) - a client-actionable "upgrade your
 * plan" state, never a server bug. See {@link TenantLimits} for what's
 * actually capped and why enforcement lives where it does.
 */
public class TenantLimitExceededException extends RuntimeException {

    public TenantLimitExceededException(String message) {
        super(message);
    }
}
