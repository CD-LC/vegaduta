package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.ReferralRedeemResponse;
import com.agenticai.core.tenancy.dto.ReferralStatusResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Referral program (PRICING-MONETIZATION-AUDIT-2026-07-31.md §8.5/D3) - {@link
 * ReferralService}'s code generation, eligibility gates, and both-sides grant
 * mechanics. Same plain-Mockito style as {@link TrialGrantServiceTest} (the
 * other comp-grant-riding growth flow); the entity-level comp-grant state
 * machine itself is covered by {@code TenantTest}/{@code CompGrantControllerTest}.
 */
class ReferralServiceTest {

    private static final String REDEEMER_SCHEMA = "acme";
    private static final String CODE = "BCDFGHJK";

    private TenantRepository tenantRepository;
    private ReferralService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        service = new ReferralService(tenantRepository);
    }

    private static Tenant tenant(String name, String schema) {
        Tenant tenant = new Tenant(
            name, TenantPlan.FREE, schema, "agentic-ai", AccountType.B2C, ExperienceMode.SIMPLE
        );
        // Same reasoning as TrialGrantServiceTest#freeTenant: id is only ever
        // populated by Hibernate on persist, and ReferralService logs/links ids.
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    /** A brand-new FREE tenant (createdAt = now, from the constructor) - redeem-eligible. */
    private static Tenant newFreeTenant() {
        return tenant("Acme", REDEEMER_SCHEMA);
    }

    private static Tenant referrerWithCode() {
        Tenant referrer = tenant("Referrer Co", "referrer");
        referrer.assignReferralCode(CODE);
        return referrer;
    }

    private void stubRedeemerLockedLookup(Tenant redeemer) {
        when(tenantRepository.findBySchemaNameForUpdate(REDEEMER_SCHEMA)).thenReturn(Optional.of(redeemer));
    }

    private void stubReferrerLockedLookup(Tenant referrer) {
        when(tenantRepository.findByReferralCodeForUpdate(CODE)).thenReturn(Optional.of(referrer));
    }

    // ---- GET /api/tenant/referral ------------------------------------------

    @Test
    void statusLazilyGeneratesAndPersistsCodeOnFirstRead() {
        Tenant tenant = newFreeTenant();
        when(tenantRepository.findBySchemaName(REDEEMER_SCHEMA)).thenReturn(Optional.of(tenant));
        when(tenantRepository.findBySchemaNameForUpdate(REDEEMER_SCHEMA)).thenReturn(Optional.of(tenant));
        when(tenantRepository.existsByReferralCode(anyString())).thenReturn(false);
        when(tenantRepository.countByReferredByTenantId(tenant.getId())).thenReturn(0L);

        ReferralStatusResponse status = service.status(REDEEMER_SCHEMA);

        assertNotNull(status.code());
        assertEquals(ReferralService.CODE_LENGTH, status.code().length());
        assertEquals(status.code(), status.code().toUpperCase(java.util.Locale.ROOT));
        assertEquals(status.code(), tenant.getReferralCode());
        assertEquals(0L, status.redemptionsCount());
        assertFalse(status.redeemedByMe());
        assertTrue(status.canRedeem());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void statusReturnsExistingCodeWithoutRegenerating() {
        Tenant tenant = newFreeTenant();
        tenant.assignReferralCode(CODE);
        when(tenantRepository.findBySchemaName(REDEEMER_SCHEMA)).thenReturn(Optional.of(tenant));
        when(tenantRepository.countByReferredByTenantId(tenant.getId())).thenReturn(3L);

        ReferralStatusResponse status = service.status(REDEEMER_SCHEMA);

        assertEquals(CODE, status.code());
        assertEquals(3L, status.redemptionsCount());
        // A stable code never rotates: no lock, no regeneration, no save.
        verify(tenantRepository, never()).findBySchemaNameForUpdate(anyString());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void statusReportsRedeemedAndNotRedeemableAfterRedemption() {
        Tenant tenant = newFreeTenant();
        tenant.assignReferralCode(CODE);
        tenant.redeemReferral(UUID.randomUUID(), Instant.now());
        when(tenantRepository.findBySchemaName(REDEEMER_SCHEMA)).thenReturn(Optional.of(tenant));
        when(tenantRepository.countByReferredByTenantId(tenant.getId())).thenReturn(0L);

        ReferralStatusResponse status = service.status(REDEEMER_SCHEMA);

        assertTrue(status.redeemedByMe());
        assertFalse(status.canRedeem());
    }

    @Test
    void statusReportsNotRedeemableForOldWorkspace() {
        Tenant tenant = newFreeTenant();
        tenant.assignReferralCode(CODE);
        ReflectionTestUtils.setField(tenant, "createdAt", Instant.now().minus(Duration.ofDays(31)));
        when(tenantRepository.findBySchemaName(REDEEMER_SCHEMA)).thenReturn(Optional.of(tenant));
        when(tenantRepository.countByReferredByTenantId(tenant.getId())).thenReturn(0L);

        assertFalse(service.status(REDEEMER_SCHEMA).canRedeem());
    }

    // ---- POST /api/tenant/referral/redeem ----------------------------------

    @Test
    void redeemGrantsOneMonthPremiumCompToBothSides() {
        Tenant redeemer = newFreeTenant();
        Tenant referrer = referrerWithCode();
        stubRedeemerLockedLookup(redeemer);
        stubReferrerLockedLookup(referrer);
        when(tenantRepository.countByReferredByTenantIdAndReferralRedeemedAtAfter(eq(referrer.getId()), any()))
            .thenReturn(0L);

        ReferralRedeemResponse response = service.redeem(REDEEMER_SCHEMA, CODE);

        assertTrue(response.granted());
        assertEquals(TenantPlan.PREMIUM, response.plan());
        assertNotNull(response.expiresAt());

        // Redeemer: comp-grant primitive rides - plan flips, comp fields set,
        // reason carries the code, gateway state untouched.
        assertEquals(TenantPlan.PREMIUM, redeemer.getPlan());
        assertEquals("referral-redeemed:" + CODE, redeemer.getCompPlanReason());
        assertNotNull(redeemer.getCompPlanGrantedAt());
        assertEquals(response.expiresAt(), redeemer.getCompPlanExpiresAt());
        assertNull(redeemer.getGatewaySubscriptionId());
        // ~1 calendar month out (30/31 days depending on the month).
        long days = Duration.between(redeemer.getCompPlanGrantedAt(), redeemer.getCompPlanExpiresAt()).toDays();
        assertTrue(days >= 28 && days <= 31, "expected ~1 calendar month, got " + days + " days");

        // The durable one-per-tenant redemption record.
        assertEquals(referrer.getId(), redeemer.getReferredByTenantId());
        assertEquals(redeemer.getCompPlanGrantedAt(), redeemer.getReferralRedeemedAt());

        // Referrer (FREE): mirror grant with the reward reason, same expiry.
        assertEquals(TenantPlan.PREMIUM, referrer.getPlan());
        assertEquals("referral-reward:" + CODE, referrer.getCompPlanReason());
        assertEquals(response.expiresAt(), referrer.getCompPlanExpiresAt());
        // The referrer's own redemption record is untouched - rewards don't
        // consume the referrer's one redemption.
        assertNull(referrer.getReferredByTenantId());

        verify(tenantRepository).save(redeemer);
        verify(tenantRepository).save(referrer);
    }

    @Test
    void redeemRejectedForUnknownCode() {
        Tenant redeemer = newFreeTenant();
        stubRedeemerLockedLookup(redeemer);
        when(tenantRepository.findByReferralCodeForUpdate(CODE)).thenReturn(Optional.empty());

        ReferralNotEligibleException thrown =
            assertThrows(ReferralNotEligibleException.class, () -> service.redeem(REDEEMER_SCHEMA, CODE));
        assertTrue(thrown.getMessage().contains("isn't recognized"));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void redeemRejectedForSelfReferral() {
        Tenant redeemer = newFreeTenant();
        redeemer.assignReferralCode(CODE);
        stubRedeemerLockedLookup(redeemer);

        ReferralNotEligibleException thrown =
            assertThrows(ReferralNotEligibleException.class, () -> service.redeem(REDEEMER_SCHEMA, CODE));
        assertEquals("You can't redeem your own referral code.", thrown.getMessage());
        // Rejected on the redeemer's own code before any referrer lookup.
        verify(tenantRepository, never()).findByReferralCodeForUpdate(anyString());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void redeemRejectedWhenAlreadyRedeemed_evenAfterCompReverted() {
        Tenant redeemer = newFreeTenant();
        // Simulate a past redemption whose comp already lapsed and was reverted:
        // comp fields cleared, plan back to FREE - only the referral record
        // remains, and it must still block a second redemption.
        redeemer.redeemReferral(UUID.randomUUID(), Instant.now().minus(Duration.ofDays(20)));
        redeemer.revertComplimentaryGrant();
        stubRedeemerLockedLookup(redeemer);

        ReferralNotEligibleException thrown =
            assertThrows(ReferralNotEligibleException.class, () -> service.redeem(REDEEMER_SCHEMA, CODE));
        assertEquals("This workspace has already redeemed a referral code.", thrown.getMessage());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void redeemRejectedWhenCompAlreadyActive() {
        Tenant redeemer = newFreeTenant();
        // E.g. the event trial is live: comp fields set (and plan PREMIUM). The
        // comp-active check fires first, naming the real conflict.
        Instant now = Instant.now();
        redeemer.grantComplimentaryPlan(TenantPlan.PREMIUM, now, now.plus(Duration.ofDays(7)), "event-trial:whatsapp-gate");
        stubRedeemerLockedLookup(redeemer);

        ReferralNotEligibleException thrown =
            assertThrows(ReferralNotEligibleException.class, () -> service.redeem(REDEEMER_SCHEMA, CODE));
        assertTrue(thrown.getMessage().contains("complimentary plan active"));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void redeemRejectedWhenNotOnFreePlan() {
        Tenant redeemer = newFreeTenant();
        redeemer.changePlan(TenantPlan.PREMIUM);
        stubRedeemerLockedLookup(redeemer);

        assertThrows(ReferralNotEligibleException.class, () -> service.redeem(REDEEMER_SCHEMA, CODE));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void redeemRejectedWhenWorkspaceOlderThanThirtyDays() {
        Tenant redeemer = newFreeTenant();
        ReflectionTestUtils.setField(redeemer, "createdAt", Instant.now().minus(Duration.ofDays(31)));
        stubRedeemerLockedLookup(redeemer);

        ReferralNotEligibleException thrown =
            assertThrows(ReferralNotEligibleException.class, () -> service.redeem(REDEEMER_SCHEMA, CODE));
        assertTrue(thrown.getMessage().contains("first 30 days"));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void redeemRejectedWhenReferrerCapReached() {
        Tenant redeemer = newFreeTenant();
        Tenant referrer = referrerWithCode();
        stubRedeemerLockedLookup(redeemer);
        stubReferrerLockedLookup(referrer);
        when(tenantRepository.countByReferredByTenantIdAndReferralRedeemedAtAfter(eq(referrer.getId()), any()))
            .thenReturn((long) ReferralService.MAX_REFERRER_GRANTS_PER_YEAR);

        ReferralNotEligibleException thrown =
            assertThrows(ReferralNotEligibleException.class, () -> service.redeem(REDEEMER_SCHEMA, CODE));
        assertTrue(thrown.getMessage().contains("reached its limit"));
        assertEquals(TenantPlan.FREE, redeemer.getPlan());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void redeemSkipsReferrerRewardWhenReferrerIsPaying() {
        Tenant redeemer = newFreeTenant();
        Tenant referrer = referrerWithCode();
        // A paying referrer: comp-granting them would overwrite the paid plan
        // now and revert them to FREE at expiry - reward must be skipped, but
        // the redemption itself still succeeds.
        referrer.changePlan(TenantPlan.UNLIMITED);
        referrer.recordGatewaySubscription("razorpay", "sub_123", "active", null);
        stubRedeemerLockedLookup(redeemer);
        stubReferrerLockedLookup(referrer);
        when(tenantRepository.countByReferredByTenantIdAndReferralRedeemedAtAfter(eq(referrer.getId()), any()))
            .thenReturn(0L);

        ReferralRedeemResponse response = service.redeem(REDEEMER_SCHEMA, CODE);

        assertTrue(response.granted());
        assertEquals(TenantPlan.PREMIUM, redeemer.getPlan());
        // Referrer untouched: paid plan intact, no comp fields written.
        assertEquals(TenantPlan.UNLIMITED, referrer.getPlan());
        assertNull(referrer.getCompPlanExpiresAt());
        assertNull(referrer.getCompPlanReason());
        verify(tenantRepository).save(redeemer);
        verify(tenantRepository, never()).save(referrer);
    }

    @Test
    void redeemRefreshesReferrerCompWhenOneIsAlreadyLive() {
        Tenant redeemer = newFreeTenant();
        Tenant referrer = referrerWithCode();
        // Referrer already holds a live comp (e.g. an earlier referral reward):
        // the new reward refreshes the expiry rather than stacking - the same
        // overwrite semantics CompGrantService documents.
        Instant earlier = Instant.now().minus(Duration.ofDays(10));
        referrer.grantComplimentaryPlan(TenantPlan.PREMIUM, earlier, earlier.plus(Duration.ofDays(30)), "referral-reward:OLD");
        stubRedeemerLockedLookup(redeemer);
        stubReferrerLockedLookup(referrer);
        when(tenantRepository.countByReferredByTenantIdAndReferralRedeemedAtAfter(eq(referrer.getId()), any()))
            .thenReturn(1L);

        ReferralRedeemResponse response = service.redeem(REDEEMER_SCHEMA, CODE);

        assertEquals(response.expiresAt(), referrer.getCompPlanExpiresAt());
        assertEquals("referral-reward:" + CODE, referrer.getCompPlanReason());
        verify(tenantRepository).save(referrer);
    }
}
