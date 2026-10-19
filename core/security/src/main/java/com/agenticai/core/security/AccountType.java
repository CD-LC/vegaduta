package com.agenticai.core.security;

/**
 * Who provisioned a tenant - kept as a stored fact about each tenant (and
 * as a stable enum so existing tenant rows/schemas never need a migration)
 * but, as of the universal-tier product decision, no longer changes what a
 * tenant is allowed to do. Every tenant - B2B, B2C, or FAMILY - gets the
 * exact same machine-enforced limits (see {@link TenantLimits} and {@link
 * com.agenticai.core.tenancy.TenantLimitsServiceImpl}): 2 users and 5
 * agents, full feature set either way. There is deliberately no signup
 * screen asking a person to pick one anymore - {@code SignupRequest}
 * defaults every new tenant to {@link #B2C} (see its {@code
 * accountTypeOrDefault()}) since there is nothing left for the person to
 * meaningfully choose between.
 */
public enum AccountType {
    B2B,
    B2C,
    FAMILY
}
