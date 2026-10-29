package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage for the {@link TenantPlanState} halves {@link TenantTest} does not reach:
 * the gateway-subscription columns, the D5 past-due / cancel-scheduled bookkeeping, {@code
 * downgradeToFree}, the two-arg {@code confirmUpgrade} (multi-gateway), the event-trial claim
 * record, and the referral columns. Exercised through {@link Tenant}, the only subclass - pure
 * entity logic, no mocks.
 */
class TenantPlanStateTest {

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
    }

    @Test
    void recordGatewaySubscriptionStoresSignupTimeGatewayState() {
        Instant trialEnd = Instant.now().plusSeconds(86400);

        tenant.recordGatewaySubscription("razorpay", "sub_signup", "created", trialEnd);

        assertEquals("razorpay", tenant.getPaymentGateway());
        assertEquals("sub_signup", tenant.getGatewaySubscriptionId());
        assertEquals("created", tenant.getSubscriptionStatus());
        assertEquals(trialEnd, tenant.getTrialEndsAt());
        // Written later by the webhook that links a customer - still unset here.
        assertNull(tenant.getGatewayCustomerId());
        assertNull(tenant.getCurrentPeriodEnd());
    }

    @Test
    void scheduleCancellationRecordsIntentWithoutDroppingThePlan() {
        tenant.changePlan(TenantPlan.PREMIUM);

        tenant.scheduleCancellation("active");

        assertTrue(tenant.isCancelScheduled());
        assertEquals("active", tenant.getSubscriptionStatus());
        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
    }

    @Test
    void markPastDueOpensTheGraceWindowAndFirstHaltWins() {
        Instant first = Instant.now();
        Instant later = first.plusSeconds(3600);

        tenant.markPastDue("halted", first);
        tenant.markPastDue("halted", later); // redelivered event - must not reset the clock

        assertTrue(tenant.isPastDue());
        assertEquals(first, tenant.getPastDueSince());
        assertEquals("halted", tenant.getSubscriptionStatus());
    }

    @Test
    void clearPastDueClosesTheGraceWindowAndKeepsThePlan() {
        tenant.changePlan(TenantPlan.PREMIUM);
        tenant.markPastDue("halted", Instant.now());

        tenant.clearPastDue("active");

        assertFalse(tenant.isPastDue());
        assertNull(tenant.getPastDueSince());
        assertEquals("active", tenant.getSubscriptionStatus());
        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
    }

    @Test
    void downgradeToFreeSettlesThePlanAndClosesEveryFailedPaymentChannel() {
        tenant.changePlan(TenantPlan.PREMIUM);
        tenant.recordGatewaySubscription("razorpay", "sub_paid", "active", null);
        tenant.scheduleCancellation("active");
        tenant.markPastDue("halted", Instant.now());
        tenant.grantComplimentaryPlan(
                TenantPlan.UNLIMITED, Instant.now(), Instant.now(), "overlapping-promo");

        tenant.downgradeToFree("cancelled");

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertEquals("cancelled", tenant.getSubscriptionStatus());
        assertFalse(tenant.isCancelScheduled());
        assertFalse(tenant.isPastDue());
        // The comp-grant bookkeeping is cleared too, or the reverter would
        // later overwrite this landing plan with planBeforeCompGrant.
        assertNull(tenant.getCompPlanExpiresAt());
        assertNull(tenant.getPlanBeforeCompGrant());
        // Kept for support/debugging: which subscription the paid era was.
        assertEquals("sub_paid", tenant.getGatewaySubscriptionId());
    }

    @Test
    void confirmUpgradeWithGatewayNameRecordsWhichGatewayConfirmed() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_stripe");

        tenant.confirmUpgrade("active", "stripe");

        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        assertEquals("stripe", tenant.getPaymentGateway());
        assertEquals("sub_stripe", tenant.getGatewaySubscriptionId());
        assertNull(tenant.getPendingPlan());
    }

    @Test
    void confirmUpgradeWithNullOrBlankGatewayNamePreservesTheStoredGateway() {
        tenant.recordGatewaySubscription("razorpay", "sub_signup", "created", null);
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_upgrade");

        tenant.confirmUpgrade("active", null);
        assertEquals("razorpay", tenant.getPaymentGateway());

        tenant.beginUpgrade(TenantPlan.UNLIMITED, "sub_upgrade_2");
        tenant.confirmUpgrade("active", "  ");
        assertEquals("razorpay", tenant.getPaymentGateway());
    }

    @Test
    void confirmUpgradeClearsALiveCompGrantSoTheReverterCannotUndoThePurchase() {
        tenant.grantComplimentaryPlan(
                TenantPlan.PREMIUM, Instant.now(), Instant.now(), "trial");
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_paid");

        tenant.confirmUpgrade("active");

        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        assertNull(tenant.getCompPlanExpiresAt());
        assertNull(tenant.getPlanBeforeCompGrant());
    }

    @Test
    void beginUpgradeStampsWhenTheCheckoutStarted() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_123");

        assertNotNull(tenant.getPendingUpgradeStartedAt());

        tenant.cancelPendingUpgrade();
        assertNull(tenant.getPendingUpgradeStartedAt());
    }

    @Test
    void claimEventTrialWritesTheDurableOnePerTenantRecord() {
        Instant claimed = Instant.now();
        Instant expires = claimed.plusSeconds(7 * 86400);

        tenant.claimEventTrial("first-agent-created", claimed, expires);

        assertEquals("first-agent-created", tenant.getEventTrialTrigger());
        assertEquals(claimed, tenant.getEventTrialClaimedAt());
        assertEquals(expires, tenant.getEventTrialExpiresAt());
    }

    @Test
    void assignReferralCodeIsFirstWriteWinsNeverRotates() {
        tenant.assignReferralCode("ABC123");
        tenant.assignReferralCode("XYZ999");

        assertEquals("ABC123", tenant.getReferralCode());
    }

    @Test
    void redeemReferralRecordsWhoseCodeThisTenantUsed() {
        UUID referrer = UUID.randomUUID();
        Instant at = Instant.now();

        tenant.redeemReferral(referrer, at);

        assertEquals(referrer, tenant.getReferredByTenantId());
        assertEquals(at, tenant.getReferralRedeemedAt());
    }
}
