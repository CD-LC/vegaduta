package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantProvisioningRecorder#activate} is the one place the 2026-09-01
 * new-signup UNLIMITED trial (direct product decision, ahead of the public
 * launch: every brand-new self-service tenant gets every feature for a
 * window, not FREE from day one - extended 30 days -> 3 CALENDAR months on
 * 2026-09-12) is actually granted - {@link
 * TenantProvisioningServiceTest} mocks this class entirely, so none of its
 * tests exercise the real grant logic. Plain JUnit 5 + Mockito, same posture
 * as that sibling test - no live Postgres.
 */
class TenantProvisioningRecorderTest {

    private TenantRepository tenantRepository;
    private AppUserRepository appUserRepository;
    private TenantProvisioningRecorder recorder;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        recorder = new TenantProvisioningRecorder(tenantRepository, appUserRepository);
    }

    private static Tenant freeTenant() {
        return new Tenant("Acme", TenantPlan.FREE, "acme", "agentic-ai", AccountType.B2C, ExperienceMode.SIMPLE);
    }

    @Test
    void aFreeSelfServiceSignupIsGrantedTheThreeMonthUnlimitedTrial() {
        Tenant tenant = freeTenant();
        UUID tenantId = tenant.getId();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        Instant before = Instant.now();
        // paymentGateway == null - exactly what both real callers
        // (TenantProvisioningService#signup / #onboardExistingUser) pass.
        recorder.activate(tenantId, "kc-user-1", "admin@acme.test", null, null, null, null);
        Instant after = Instant.now();

        assertEquals(TenantPlan.UNLIMITED, tenant.getPlan());
        assertEquals("signup-trial", tenant.getCompPlanReason());
        assertNotNull(tenant.getCompPlanExpiresAt());
        // Expiry is the LATER of the launch promotion (SIGNUP_FREE_UNTIL,
        // 2027-01-01) and the standing 3-CALENDAR-month grant. Which of the two
        // wins depends on the date this test runs, so assert the rule rather
        // than either branch - a test hardcoding "2027-01-01" starts failing
        // the day the promotion lapses, and one hardcoding "+3 months" fails
        // today. Both branches are covered explicitly below.
        //
        // Calendar months, not days: a quarter spans 89 (Dec-Feb, non-leap) to
        // 92 days, so "~90 days" would be a test that fails for three months of
        // the year. Recompute the same arithmetic production uses, against the
        // [before, after] window the grant was actually taken in - grantedAt is
        // read fresh inside activate(), not injected.
        Instant standingEarliest = before.atOffset(ZoneOffset.UTC).plusMonths(3).toInstant();
        Instant standingLatest = after.atOffset(ZoneOffset.UTC).plusMonths(3).toInstant();
        Instant expiry = tenant.getCompPlanExpiresAt();

        if (standingLatest.isAfter(TenantProvisioningRecorder.SIGNUP_FREE_UNTIL)) {
            // Late enough that the 3-month floor beats the promotion.
            assertTrue(!expiry.isBefore(standingEarliest) && !expiry.isAfter(standingLatest),
                "3-month floor should win, expected in [" + standingEarliest + ", " + standingLatest + "], got " + expiry);
        } else {
            assertEquals(TenantProvisioningRecorder.SIGNUP_FREE_UNTIL, expiry,
                "launch promotion should win and pin expiry to 2027-01-01");
        }

        // The invariant that matters regardless of which branch won, and the
        // regression this guards: nobody may ever receive LESS than the
        // standing three-month grant. A flat "free until 1 Jan 2027" would have
        // silently shortened it for anyone signing up near that date.
        assertTrue(!expiry.isBefore(standingEarliest),
            "expiry must never undercut the 3-month floor: " + expiry + " < " + standingEarliest);
        // Sanity floor: whatever the calendar does, a quarter is never < 89 days.
        assertTrue(Duration.between(before, expiry).compareTo(Duration.ofDays(89)) >= 0,
            "expected at least 89 days until expiry, got " + Duration.between(before, expiry));

        // The revert target (what CompGrantExpiryReverter restores at expiry)
        // must be the tenant's true pre-grant plan, FREE - not left comped
        // forever and not some other stale value.
        tenant.revertComplimentaryGrant();
        assertEquals(TenantPlan.FREE, tenant.getPlan());
    }

    @Test
    void aPaidGatewaySignupIsNotGrantedTheSelfServiceTrial() {
        // Not a real current caller (both real call sites pass null gateway
        // args today - see the class javadoc), but activate()'s own contract
        // is "no trial when a payment gateway is already involved" - this
        // pins that contract so a future paid-flow caller doesn't silently
        // stack a free UNLIMITED trial on top of a real subscription.
        Tenant tenant = freeTenant();
        UUID tenantId = tenant.getId();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        recorder.activate(tenantId, "kc-user-1", "admin@acme.test", "razorpay", "sub_123", "active", null);

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertNull(tenant.getCompPlanExpiresAt());
        assertNull(tenant.getCompPlanReason());
    }
}
