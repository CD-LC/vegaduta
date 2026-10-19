package com.agenticai.core.security;

/**
 * Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - a
 * tenant's DECLARED data-residency jurisdiction, set by a platform admin
 * (see {@code PlatformTenantDataResidencyService} in {@code core/platform}).
 *
 * <p><b>Informational only, not an enforcement or routing mechanism.</b>
 * This codebase runs a single Postgres instance today - there is no
 * multi-region infrastructure for a tenant's actual data to be routed to
 * based on this value (see {@code V85__platform_hosted_llm_provider_region.sql}'s
 * header comment, which flagged this exact gap - "there is no existing
 * tenant-level geo/region field in this codebase to route against" - while
 * adding a *different*, admin-labeling-only region field to the free-tier
 * LLM provider pool). Declaring {@link #EU} here today does not move a
 * single row of that tenant's data anywhere; it only records what a
 * platform admin has told the tenant (or what the tenant has been promised)
 * about where their data is intended to live, for support/compliance
 * conversations, ahead of any real multi-region build-out.
 *
 * <p>Lives in {@code core.security} - not {@code core.tenancy}, which
 * actually owns the {@code Tenant} row this is stored on - for the exact
 * same module-cycle-avoidance reason {@link AccountType}/{@link TenantPlan}
 * already document: {@code core.platform} (which reads/writes this via
 * {@code PlatformTenantDataResidencyService}) already depends on {@code
 * core.security}, and putting this enum there keeps it reachable from any
 * future module that needs to read a tenant's declared residency without
 * that module having to depend on {@code core.tenancy} itself.
 *
 * <p>{@code UNSPECIFIED} is the default for both pre-existing and newly
 * signed-up tenants (see migration V96) - this pass does not backfill every
 * tenant to {@link #INDIA} even though that already matches this platform's
 * actual single-region hosting and its own marketing copy (the Keycloak
 * login theme's "data stays in India" trust line) - asserting a specific
 * declared jurisdiction is a decision left to a platform admin to make
 * explicitly per tenant, not something this migration should assume on
 * everyone's behalf.
 */
public enum DataResidencyRegion {
    UNSPECIFIED,
    INDIA,
    EU,
    US,
    OTHER
}
