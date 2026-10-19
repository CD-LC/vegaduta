package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.TrialClaimResponse;
import com.agenticai.core.tenancy.dto.TrialStatusResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Event-triggered 7-day Premium trial (PRICING-MONETIZATION-AUDIT-2026-07-31.md
 * §5.4/B6) - {@link TrialGrantService}'s eligibility gate and grant mechanics.
 * Same plain-Mockito style as {@link TenantUpgradeServiceTest} (the paid half of
 * plan changes); the entity-level comp-grant state machine itself is covered by
 * {@link TenantTest}/{@code CompGrantControllerTest}.
 */
class TrialGrantServiceTest {

    private static final String SCHEMA = "acme";

    private TenantRepository tenantRepository;
    private TrialGrantService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        service = new TrialGrantService(tenantRepository);
    }

    private static Tenant freeTenant() {
        Tenant tenant = new Tenant(
            "Acme", TenantPlan.FREE, SCHEMA, "agentic-ai", AccountType.B2C, ExperienceMode.SIMPLE
        );
        // Same reasoning as TenantUpgradeServiceTest#freeTenant: id is only ever
        // populated by Hibernate on persist, and TrialGrantService logs tenant.getId().
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    private void stubLockedLookup(Tenant tenant) {
        when(tenantRepository.findBySchemaNameForUpdate(SCHEMA)).thenReturn(Optional.of(tenant));
    }

    private void stubPlainLookup(Tenant tenant) {
        when(tenantRepository.findBySchemaName(SCHEMA)).thenReturn(Optional.of(tenant));
    }

    @Test
    void claimGrantsSevenDayPremiumCompAndRecordsTheClaim() {
        Tenant tenant = freeTenant();
        stubLockedLookup(tenant);

        TrialClaimResponse response = service.claim(SCHEMA, "whatsapp-gate");

        assertTrue(response.granted());
        assertEquals(TenantPlan.PREMIUM, response.plan());
        assertNotNull(response.expiresAt());

        // The grant rides the comp-grant primitive: plan flips, comp fields set,
        // reason carries the trigger, gateway state untouched.
        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        assertEquals("event-trial:whatsapp-gate", tenant.getCompPlanReason());
        assertNotNull(tenant.getCompPlanGrantedAt());
        assertEquals(
            Duration.ofDays(7),
            Duration.between(tenant.getCompPlanGrantedAt(), tenant.getCompPlanExpiresAt())
        );
        assertNull(tenant.getGatewaySubscriptionId());
        assertNull(tenant.getSubscriptionStatus());

        // The durable one-per-tenant claim record.
        assertEquals("whatsapp-gate", tenant.getEventTrialTrigger());
        assertEquals(tenant.getCompPlanGrantedAt(), tenant.getEventTrialClaimedAt());
        assertEquals(tenant.getCompPlanExpiresAt(), tenant.getEventTrialExpiresAt());
        assertEquals(response.expiresAt(), tenant.getEventTrialExpiresAt());

        verify(tenantRepository).save(tenant);
    }

    @Test
    void claimRejectedWhenAlreadyClaimed_evenAfterCompReverted() {
        Tenant tenant = freeTenant();
        // Simulate a previously claimed trial whose comp already lapsed and was
        // reverted: comp fields cleared, plan back to FREE - only the
        // event_trial_* record remains, and it must still block a second claim.
        Instant then = Instant.now().minus(Duration.ofDays(30));
        tenant.claimEventTrial("edit-lock", then, then.plus(Duration.ofDays(7)));
        tenant.revertComplimentaryGrant();
        stubLockedLookup(tenant);

        TrialNotEligibleException thrown =
            assertThrows(TrialNotEligibleException.class, () -> service.claim(SCHEMA, "whatsapp-gate"));
        assertEquals("This workspace has already used its one-time Premium trial.", thrown.getMessage());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void claimRejectedWhenNotOnFreePlan() {
        Tenant tenant = freeTenant();
        tenant.changePlan(TenantPlan.PREMIUM);
        stubLockedLookup(tenant);

        assertThrows(TrialNotEligibleException.class, () -> service.claim(SCHEMA, "whatsapp-gate"));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void claimRejectedWhenTenantEverHadPaidSubscription() {
        Tenant tenant = freeTenant();
        // A lapsed subscriber back on FREE: gateway columns still record the
        // old subscription - win-back case, not trial case.
        tenant.recordGatewaySubscription("razorpay", "sub_123", "cancelled", null);
        stubLockedLookup(tenant);

        assertThrows(TrialNotEligibleException.class, () -> service.claim(SCHEMA, "whatsapp-gate"));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void claimRejectedWhileUpgradeIsPending() {
        Tenant tenant = freeTenant();
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_pending_1");
        stubLockedLookup(tenant);

        assertThrows(TrialNotEligibleException.class, () -> service.claim(SCHEMA, "whatsapp-gate"));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void statusEligibleForFreshFreeTenant() {
        stubPlainLookup(freeTenant());

        TrialStatusResponse status = service.status(SCHEMA);

        assertTrue(status.eligible());
        assertFalse(status.claimed());
        assertNull(status.expiresAt());
        assertNull(status.reason());
    }

    @Test
    void statusReportsClaimedWithDurableExpiry() {
        Tenant tenant = freeTenant();
        Instant then = Instant.now().minus(Duration.ofDays(30));
        Instant expiry = then.plus(Duration.ofDays(7));
        tenant.claimEventTrial("whatsapp-gate", then, expiry);
        tenant.revertComplimentaryGrant(); // trial long lapsed, comp fields gone
        stubPlainLookup(tenant);

        TrialStatusResponse status = service.status(SCHEMA);

        assertFalse(status.eligible());
        assertTrue(status.claimed());
        assertEquals(expiry, status.expiresAt());
        assertNotNull(status.reason());
    }

    @Test
    void statusIneligibleWithReasonForPaidTenant() {
        Tenant tenant = freeTenant();
        tenant.changePlan(TenantPlan.UNLIMITED);
        stubPlainLookup(tenant);

        TrialStatusResponse status = service.status(SCHEMA);

        assertFalse(status.eligible());
        assertFalse(status.claimed());
        assertNotNull(status.reason());
    }
}
