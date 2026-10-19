package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link EditLockPolicy} gates every mutating agent/workflow edit for
 * FREE-tier tenants past a 24h grace window - see its own javadoc. This is
 * revenue-and-product-decision logic (a wrong answer either locks a paying
 * customer out of their own data, or silently fails to enforce the tier
 * boundary the GTM strategy doc calls for), and had zero test coverage
 * before this suite.
 *
 * <p>Deliberately does not attempt to pin down the exact instant boundary
 * ({@code createdAt.plus(FREE_TIER_GRACE_WINDOW)} vs. real {@code
 * Instant.now()} at assertion time) - {@link EditLockPolicy} has no
 * injectable clock, so an exact-boundary test would be inherently flaky.
 * Every case here is deliberately well clear of the boundary in one
 * direction or the other.
 */
class EditLockPolicyTest {

    @Test
    void freeTenantWellWithinGraceWindowIsNotLocked() {
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS);
        assertFalse(EditLockPolicy.isLocked(TenantPlan.FREE, createdAt));
    }

    @Test
    void freeTenantCreatedJustNowIsNotLocked() {
        assertFalse(EditLockPolicy.isLocked(TenantPlan.FREE, Instant.now()));
    }

    @Test
    void freeTenantWellPastGraceWindowIsLocked() {
        Instant createdAt = Instant.now().minus(48, ChronoUnit.HOURS);
        assertTrue(EditLockPolicy.isLocked(TenantPlan.FREE, createdAt));
    }

    @Test
    void freeTenantOneHourPastGraceWindowIsLocked() {
        Instant createdAt = Instant.now().minus(25, ChronoUnit.HOURS);
        assertTrue(EditLockPolicy.isLocked(TenantPlan.FREE, createdAt));
    }

    @Test
    void freeTenantOneHourBeforeGraceWindowIsNotLocked() {
        Instant createdAt = Instant.now().minus(23, ChronoUnit.HOURS);
        assertFalse(EditLockPolicy.isLocked(TenantPlan.FREE, createdAt));
    }

    @Test
    void premiumTenantIsNeverLockedRegardlessOfAge() {
        Instant longAgo = Instant.now().minus(365, ChronoUnit.DAYS);
        assertFalse(EditLockPolicy.isLocked(TenantPlan.PREMIUM, longAgo));
    }

    @Test
    void unlimitedTenantIsNeverLockedRegardlessOfAge() {
        Instant longAgo = Instant.now().minus(365, ChronoUnit.DAYS);
        assertFalse(EditLockPolicy.isLocked(TenantPlan.UNLIMITED, longAgo));
    }

    @Test
    void nullCreatedAtIsTreatedAsNotLockedForFreeTenant() {
        // Defensive default per the method's own javadoc - every real
        // Agent/Workflow row always has a createdAt, but a null must not
        // throw or default to "locked" (which would be a fail-closed
        // surprise for legitimately unlocked data).
        assertFalse(EditLockPolicy.isLocked(TenantPlan.FREE, null));
    }

    @Test
    void nullCreatedAtIsTreatedAsNotLockedForPremiumTenant() {
        assertFalse(EditLockPolicy.isLocked(TenantPlan.PREMIUM, null));
    }

    @Test
    void graceWindowConstantIsTwentyFourHours() {
        // Pins down the literal product decision (Subscription Plan & GTM
        // Strategy doc, Section 4, Decision 1) so a future edit changing
        // this constant is a conscious, visible diff in this test file,
        // not a silent behavior change.
        assertTrue(EditLockPolicy.FREE_TIER_GRACE_WINDOW.equals(java.time.Duration.ofHours(24)));
    }
}
