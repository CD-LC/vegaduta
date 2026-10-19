package com.agenticai.core.tenancy;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySchemaName(String schemaName);

    /**
     * Event-triggered trial (B6) - {@code TrialGrantService#claim}'s lookup. {@code SELECT ... FOR
     * UPDATE}: the one-per-tenant trial claim is a read-check-write ({@code eventTrialClaimedAt ==
     * null} then claim), and two concurrent claim requests racing that check without a row lock
     * could both pass it. Same row, so the second transaction blocks until the first commits, then
     * sees the claim and gets the 409.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tenant t WHERE t.schemaName = :schemaName")
    Optional<Tenant> findBySchemaNameForUpdate(@Param("schemaName") String schemaName);

    Optional<Tenant> findByKeycloakRealm(String keycloakRealm);

    /**
     * Referral program (§8.5/D3) - {@code ReferralService#redeem}'s referrer lookup, {@code SELECT
     * ... FOR UPDATE} for the same reason as {@link #findBySchemaNameForUpdate}: the rolling-year
     * 12-grant cap is a read-count-write, and two concurrent redemptions of the same code racing
     * that count without a row lock could both pass it at 11. (Two tenants mutually redeeming each
     * other's codes concurrently could in theory deadlock on the two row locks; Postgres detects
     * and aborts one, which simply retries or 409s - acceptable for an event this rare.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tenant t WHERE t.referralCode = :referralCode")
    Optional<Tenant> findByReferralCodeForUpdate(@Param("referralCode") String referralCode);

    /**
     * Referral program - collision check for {@code ReferralService}'s lazy code generation (V159
     * unique constraint is the backstop).
     */
    boolean existsByReferralCode(String referralCode);

    /**
     * Referral program - all-time redemption count of this tenant's code, {@code GET
     * /api/tenant/referral}'s {@code redemptionsCount}.
     */
    long countByReferredByTenantId(UUID referredByTenantId);

    /**
     * Referral program - the rolling-365-day window the 12-grants-per-year anti-abuse cap counts.
     */
    long countByReferredByTenantIdAndReferralRedeemedAtAfter(
            UUID referredByTenantId, Instant cutoff);

    boolean existsByName(String name);

    /**
     * Signup retry (B4): the leftover row of an earlier failed attempt for the same organization
     * name.
     */
    Optional<Tenant> findByName(String name);

    /** Autonomy/Guardrails Strategy Stage C - {@code TenantSchemaIterator}'s source list. */
    List<Tenant> findByStatus(TenantStatus status);

    /**
     * The platform-admin roster's source list ({@code PlatformTenantMetricsService}): ACTIVE
     * tenants that are NOT anonymous {@code /try} sandboxes. {@link #findByStatus} alone counted
     * every demo sandbox as a tenant, so the admin "active tenants" figure rose with landing-page
     * traffic rather than with registrations. Native because {@code is_demo} (V126) is deliberately
     * unmapped on {@link Tenant} - see {@code DemoSessionRecorder}'s javadoc. {@code
     * TenantSchemaIterator} keeps using {@link #findByStatus}: background jobs must still visit a
     * live sandbox's schema.
     */
    @Query(
            value =
                    "SELECT * FROM control_plane.tenant WHERE status = 'ACTIVE' AND NOT is_demo"
                            + " ORDER BY created_at",
            nativeQuery = true)
    List<Tenant> findActiveExcludingDemo();

    /**
     * The platform-admin roster (2026-10-09): every registered (NOT {@code is_demo}) tenant that
     * still exists - ACTIVE plus the lifecycle states an operator needs to see and act on
     * (SUSPENDED, PROVISIONING, PROVISIONING_FAILED). ERASED rows are terminal records, not
     * tenants. Oldest first, same as {@link #findActiveExcludingDemo}.
     */
    @Query(
            value =
                    "SELECT * FROM control_plane.tenant WHERE status IN ('ACTIVE', 'SUSPENDED',"
                            + " 'PROVISIONING', 'PROVISIONING_FAILED') AND NOT is_demo ORDER BY"
                            + " created_at",
            nativeQuery = true)
    List<Tenant> findRosterExcludingDemo();

    /**
     * {@link #findActiveExcludingDemo}'s size without loading the rows - the admin overview tile.
     */
    @Query(
            value =
                    "SELECT COUNT(*) FROM control_plane.tenant WHERE status = 'ACTIVE' AND NOT"
                            + " is_demo",
            nativeQuery = true)
    long countActiveExcludingDemo();

    /**
     * Demo-sandbox tenant rows in one status - {@code 'ACTIVE'} is a live sandbox, {@code
     * 'SUSPENDED'} a lapsed one whose schema is still on disk ({@code DemoSessionExpiryJob}
     * suspends, it never drops). Registering does not change either figure's meaning: conversion
     * provisions a NEW real tenant and leaves the sandbox row {@code is_demo} (see {@code
     * DemoConversionService}).
     */
    @Query(
            value = "SELECT COUNT(*) FROM control_plane.tenant WHERE is_demo AND status = :status",
            nativeQuery = true)
    long countDemoByStatus(@Param("status") String status);

    /**
     * #811 - {@code RazorpayWebhookService}'s lookup: which tenant does this incoming subscription
     * event belong to.
     */
    Optional<Tenant> findByPendingGatewaySubscriptionId(String pendingGatewaySubscriptionId);

    /**
     * Freemium billing hardening (V471) - {@code StalePendingUpgradeCleanup}'s source list: tenants
     * whose self-serve upgrade was initiated longer ago than the checkout window and never
     * confirmed - an abandoned checkout leaves {@code pending_*} populated forever otherwise, and a
     * late-arriving gateway webhook could resurrect an upgrade the tenant walked away from days
     * earlier.
     */
    List<Tenant> findByPendingGatewaySubscriptionIdIsNotNullAndPendingUpgradeStartedAtBefore(
            Instant cutoff);

    /**
     * D5 (PRICING-MONETIZATION-AUDIT-2026-07-31 §8.7) - {@code RazorpayWebhookService}'s SECOND
     * lookup, tried only when the event's subscription id matched no pending upgrade: is this an
     * event about a tenant's LIVE (already-confirmed) subscription? This is how an already-paying
     * tenant's cancelled/halted/completed lifecycle finally reaches {@code Tenant.plan} - before D5
     * such events were silently ignored and a cancelled PREMIUM stayed PREMIUM forever.
     */
    Optional<Tenant> findByGatewaySubscriptionId(String gatewaySubscriptionId);

    /**
     * Academy Fast-Track Scholarship one-click fulfilment - {@code CompGrantExpiryReverter}'s
     * source list: every tenant whose complimentary plan grant has lapsed and still needs reverting
     * to FREE. {@code compPlanExpiresAt IS NULL} rows (the overwhelming majority - no comp grant
     * ever issued) are excluded by the {@code <} comparison itself.
     */
    List<Tenant> findByCompPlanExpiresAtBefore(Instant cutoff);

    /**
     * ACTIVE tenants whose live comp grant ends inside {@code (now, until]} and whose reminder
     * stage is still below {@code stage} - the candidates for one expiry reminder (see
     * CompGrantReminderJob). The stage column makes this idempotent: once a reminder is recorded
     * the tenant drops out of the result for that stage.
     */
    @Query(
            "SELECT t FROM Tenant t WHERE t.status = com.agenticai.core.tenancy.TenantStatus.ACTIVE"
                    + " AND t.compPlanExpiresAt IS NOT NULL AND t.compPlanExpiresAt > :now AND"
                    + " t.compPlanExpiresAt <= :until AND t.compPlanReminderStage < :stage")
    List<Tenant> findCompGrantsDueForReminder(
            @Param("now") Instant now, @Param("until") Instant until, @Param("stage") int stage);

    /**
     * {@code AbandonedSignupCleanupJob}'s query. {@code
     * subscriptionStatus = 'created'} specifically (not "anything other than active") means
     * "Razorpay's subscription was created at signup but never progressed past that" - the
     * webhook handler is what would ever move it to {@code authenticated}/ {@code active}/etc, so
     * in practice this simply matches every tenant that hasn't finished checkout, which is
     * exactly this job's job. Scoped to {@code status = ACTIVE} deliberately - a tenant already
     * {@code PROVISIONING_FAILED} needs no further rollback.
     */
    @Query(
            "SELECT t FROM Tenant t WHERE t.status = com.agenticai.core.tenancy.TenantStatus.ACTIVE"
                    + " AND t.subscriptionStatus = 'created' AND t.createdAt < :cutoff")
    List<Tenant> findAbandonedCheckouts(@Param("cutoff") Instant cutoff);

    /**
     * /try Agent Studio: is this schema the shared demo sandbox tenant? {@code is_demo} is
     * deliberately unmapped on {@link Tenant} (see above), so this is a native read. Used by {@code
     * TenantLimitsServiceImpl} to exempt the demo tenant from the per-schema agent/workflow plan
     * caps - every anonymous visitor shares one schema, so a FREE cap of 5 agents would be a cap on
     * the whole shop window, not on one visitor. The studio enforces its own per-session and per-IP
     * caps instead.
     */
    @Query(
            value =
                    "SELECT COALESCE(is_demo, FALSE) FROM control_plane.tenant WHERE schema_name ="
                            + " :schemaName",
            nativeQuery = true)
    Optional<Boolean> isDemoSchema(@Param("schemaName") String schemaName);
}
