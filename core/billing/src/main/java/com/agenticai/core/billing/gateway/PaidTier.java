package com.agenticai.core.billing.gateway;

/**
 * The two paid tiers {@link RazorpayPlanCatalog} knows plan ids for. This is
 * deliberately NOT {@code core/security}'s {@code TenantPlan} - this billing
 * module has never depended on the security module (see {@code
 * core/billing/pom.xml}), and {@code TenantPlan.FREE} isn't a thing a plan
 * catalog could ever resolve anyway. {@code TenantUpgradeService} does the
 * one-line {@code TenantPlan} -&gt; {@code PaidTier} mapping at the seam.
 */
public enum PaidTier {
    PREMIUM,
    UNLIMITED
}
