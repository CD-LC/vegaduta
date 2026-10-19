package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Zero test coverage existed for {@link Tenant} before this - including its #811 self-serve upgrade
 * state machine ({@link Tenant#beginUpgrade}/{@link Tenant#confirmUpgrade}/{@link
 * Tenant#cancelPendingUpgrade}), which is the exact mechanism that flips {@code Tenant.plan} on a
 * real payment (the revenue loop VEGADUTA-MASTER-ROADMAP-2026-07-19.md §1 called out as unbuilt -
 * it is not; this class's own logic already does it. See this session's
 * AGENT-COORDINATION-2026-07-18.md Status Log entry). This class covers that state machine
 * directly, no mocks needed - pure entity logic.
 */
class TenantTest {

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
    void beginUpgradeSetsPendingFieldsWithoutTouchingPlanYet() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_123");

        assertEquals(TenantPlan.PREMIUM, tenant.getPendingPlan());
        assertEquals("sub_123", tenant.getPendingGatewaySubscriptionId());
        // Not confirmed yet - the whole point of "pending".
        assertEquals(TenantPlan.FREE, tenant.getPlan());
    }

    @Test
    void beginUpgradeOverwritesAnEarlierAbandonedAttempt() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_abandoned");
        tenant.beginUpgrade(TenantPlan.UNLIMITED, "sub_retry");

        assertEquals(TenantPlan.UNLIMITED, tenant.getPendingPlan());
        assertEquals("sub_retry", tenant.getPendingGatewaySubscriptionId());
    }

    @Test
    void confirmUpgradePromotesPendingPlanAndClearsPendingFields() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_123");

        tenant.confirmUpgrade("active");

        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        assertEquals("sub_123", tenant.getGatewaySubscriptionId());
        assertEquals("active", tenant.getSubscriptionStatus());
        assertNull(tenant.getPendingPlan());
        assertNull(tenant.getPendingGatewaySubscriptionId());
    }

    @Test
    void confirmUpgradeWithNoPendingUpgradeIsANoOpNotAnError() {
        // Duplicate/late webhook delivery, or an event for a subscription
        // this tenant was never mid-upgrade for (e.g. the signup-time trial).
        tenant.confirmUpgrade("active");

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertNull(tenant.getGatewaySubscriptionId());
    }

    @Test
    void cancelPendingUpgradeClearsPendingFieldsWithoutTouchingPlan() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_123");

        tenant.cancelPendingUpgrade();

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertNull(tenant.getPendingPlan());
        assertNull(tenant.getPendingGatewaySubscriptionId());
        // The subscription id was never confirmed, so it must not leak into
        // the tenant's real (non-pending) gateway subscription id either.
        assertNull(tenant.getGatewaySubscriptionId());
    }

    @Test
    void cancelPendingUpgradeWithNothingPendingIsANoOp() {
        tenant.cancelPendingUpgrade();

        assertNull(tenant.getPendingPlan());
        assertEquals(TenantPlan.FREE, tenant.getPlan());
    }

    @Test
    void confirmUpgradeAfterCancelDoesNothingBecauseNothingIsPending() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_123");
        tenant.cancelPendingUpgrade();

        // A late "activated" webhook arriving after the user's card was
        // actually declined (subscription.halted came first) must not
        // resurrect the plan change.
        tenant.confirmUpgrade("active");

        assertEquals(TenantPlan.FREE, tenant.getPlan());
    }

    @Test
    void changePlanIsAnImmediatePlatformAdminOverrideIndependentOfPendingUpgrade() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_123");

        tenant.changePlan(TenantPlan.UNLIMITED);

        assertEquals(TenantPlan.UNLIMITED, tenant.getPlan());
        // The admin override does not touch the still-pending self-serve
        // upgrade fields - documented "deliberately does NOT touch
        // gatewaySubscriptionId/subscriptionStatus" behavior on the plan
        // field itself; pending-upgrade fields are a separate concern this
        // method's own javadoc says nothing about, so assert they're
        // unaffected too (a later webhook for sub_123 could still land).
        assertEquals(TenantPlan.PREMIUM, tenant.getPendingPlan());
        assertEquals("sub_123", tenant.getPendingGatewaySubscriptionId());
    }

    @Test
    void revertComplimentaryGrantRestoresFreeForTheOrdinaryAcademyScholarshipCase() {
        // The original, still-most-common case: a FREE tenant gets a comp
        // grant (scholarship/trial/referral), then it lapses. Must still land
        // back on FREE, not regress to some other default.
        tenant.grantComplimentaryPlan(
                TenantPlan.PREMIUM, Instant.now(), Instant.now(), "academy-fast-track-scholarship");

        tenant.revertComplimentaryGrant();

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertNull(tenant.getCompPlanExpiresAt());
        assertNull(tenant.getCompPlanGrantedAt());
        assertNull(tenant.getCompPlanReason());
    }

    @Test
    void revertComplimentaryGrantRestoresPriorPlanForAnAlreadyPayingCustomer() {
        // The gap this class had zero coverage for: a site-wide promo (or any
        // future comp grant) landing on a tenant that was already a real,
        // paying PREMIUM customer must restore PREMIUM on expiry, not drop
        // them to FREE while their gateway subscription is still active.
        tenant.changePlan(TenantPlan.PREMIUM); // simulates an existing paid subscription

        tenant.grantComplimentaryPlan(
                TenantPlan.UNLIMITED,
                Instant.now(),
                Instant.now(),
                "site-wide-2-week-unlimited-promo");
        assertEquals(TenantPlan.UNLIMITED, tenant.getPlan());

        tenant.revertComplimentaryGrant();

        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
    }

    @Test
    void reGrantingWhileAlreadyComplimentedDoesNotClobberTheOriginallyCapturedPriorPlan() {
        // A refresh/extend of an already-live grant (e.g. re-running the bulk
        // promo, or the Academy extending a scholarship) must not overwrite
        // the true original plan with the currently-comped one - otherwise
        // reverting after the SECOND grant would restore the wrong plan.
        tenant.changePlan(TenantPlan.PREMIUM);
        tenant.grantComplimentaryPlan(
                TenantPlan.UNLIMITED, Instant.now(), Instant.now(), "first-grant");

        tenant.grantComplimentaryPlan(
                TenantPlan.UNLIMITED, Instant.now(), Instant.now(), "refreshed-grant");
        tenant.revertComplimentaryGrant();

        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
    }

    @Test
    void revertComplimentaryGrantWithNoLiveGrantIsANoOpNotAnError() {
        tenant.revertComplimentaryGrant();

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertNull(tenant.getCompPlanExpiresAt());
    }

    @Test
    void reminderStageIsMonotonicAndResetByEveryGrantAndByTheRevert() {
        Tenant tenant =
                new Tenant(
                        "acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        java.time.Instant now = java.time.Instant.now();
        tenant.grantComplimentaryPlan(
                TenantPlan.UNLIMITED, now, now.plusSeconds(86400), "signup-trial");
        tenant.markCompReminderSent(2);
        tenant.markCompReminderSent(1); // never moves backwards
        org.junit.jupiter.api.Assertions.assertEquals(2, tenant.getCompPlanReminderStage());

        tenant.grantComplimentaryPlan(
                TenantPlan.UNLIMITED, now, now.plusSeconds(172800), "extended");
        org.junit.jupiter.api.Assertions.assertEquals(
                0, tenant.getCompPlanReminderStage(), "a re-grant restarts the sequence");

        tenant.markCompReminderSent(1);
        tenant.revertComplimentaryGrant();
        org.junit.jupiter.api.Assertions.assertEquals(0, tenant.getCompPlanReminderStage());
    }

    @Test
    void scrubPersonalDataClearsFreeTextAndPreferencesButKeepsIdentityAndBilling() {
        tenant.updateBranding("Acme Brand", "https://cdn.acme.test/logo.png");
        tenant.emergencyStop("Reported by Jane Doe");
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_123");

        tenant.scrubPersonalData();

        assertNull(tenant.getBrandDisplayName());
        assertNull(tenant.getBrandLogoUrl());
        assertNull(tenant.getEmergencyStopReason());
        assertNull(tenant.getReferralCode());
        assertNull(tenant.getAiModelPreferences());
        assertNull(tenant.getCompPlanReason());
        assertEquals(false, tenant.isNewsOptIn());
        // Kept: the name (deletion log / invoices reference it) and gateway state.
        assertEquals("Acme", tenant.getName());
        assertEquals("sub_123", tenant.getPendingGatewaySubscriptionId());
    }
}
