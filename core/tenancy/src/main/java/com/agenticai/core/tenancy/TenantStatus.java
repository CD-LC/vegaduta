package com.agenticai.core.tenancy;

/**
 * A tenant's provisioning lifecycle state (Phase 0 Stage 5's signup flow).
 *
 * <p>{@code PROVISIONING} exists specifically so a partially-provisioned
 * tenant (Keycloak realm created, Postgres schema not yet migrated, or vice
 * versa) is never mistaken for a working one - see the Phase 0 Build Plan's
 * Stage 5 failure-handling requirement: a tenant only reaches {@code ACTIVE}
 * once every provisioning step has succeeded.
 *
 * <p>{@code ERASED} - Master Roadmap 2026 item #22 (GDPR/data-residency
 * basics, §G) - a terminal state set by {@code TenantDataErasureService}
 * once a right-to-erasure request has completed: every Keycloak user for
 * this tenant deleted and its Postgres schema dropped. Deliberately its OWN
 * value, not a reuse of {@code SUSPENDED} - same reasoning {@code
 * Tenant.emergencyStopped}'s field-level comment already gives for keeping
 * that flag separate from this enum ("conflating two different concepts
 * would make both harder to reason about later"). The {@link Tenant} row
 * itself is deliberately NOT deleted when this is set - see {@code
 * TenantDataErasureService}'s javadoc for why keeping a minimal terminal
 * record (this status plus {@code erasedAt}/{@code erasedBy}) is the actual
 * durable evidence that erasure happened, once the tenant's own schema
 * (and whatever audit trail lived inside it) is gone.
 */
public enum TenantStatus {
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    PROVISIONING_FAILED,
    ERASED
}
