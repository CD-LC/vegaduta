package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * {@link CompGrantExpiryReverter} carries two opposite silent-and-severe
 * failure modes, neither of which surfaces an error anywhere: revert a tenant
 * whose grant has NOT expired and a customer is downgraded mid-term with no
 * complaint channel but a support ticket; fail to revert and a comp'd
 * UNLIMITED plan leaks indefinitely - and because {@code SiteWidePromoService}
 * grants through this same primitive, a filter bug here is multiplied across
 * every tenant on the platform at once. The entity-level revert semantics
 * (restore prior plan, clear bookkeeping) are already covered by {@link
 * TenantTest}; this suite pins the reverter's ORCHESTRATION contract instead:
 * the body runs only inside the advisory lock, the revert set is exactly what
 * {@code findByCompPlanExpiresAtBefore(now)} returned (no more, no fewer),
 * the cutoff is the current instant (a future cutoff would sweep live
 * grants), an empty result short-circuits without a write, and persistence is
 * one {@code saveAll} after every row was mutated. All DB-free: the
 * repository and lock are Mockito mocks, the tenants are real entities
 * (spied, so per-row calls are verifiable AND the actual plan flip is
 * asserted end-to-end).
 */
class CompGrantExpiryReverterTest {

    /**
     * Duplicated from the class under test ON PURPOSE: the stubs below only
     * match this exact name, so renaming the production constant fails these
     * tests loudly. That is the desired behavior - during a rolling deploy a
     * renamed lock key means old and new replicas lock DIFFERENT keys and
     * both run the sweep concurrently, exactly what the lock exists to stop.
     */
    private static final String JOB_LOCK_NAME = "comp-grant-expiry-revert";

    private TenantRepository tenantRepository;
    private PgAdvisoryJobLock jobLock;
    private CompGrantExpiryReverter reverter;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        jobLock = mock(PgAdvisoryJobLock.class);
        reverter = new CompGrantExpiryReverter(tenantRepository, jobLock);
    }

    /**
     * Makes the mocked lock behave like a won lock: invoke the wrapped body.
     * Keyed to the exact job name (see {@link #JOB_LOCK_NAME}) - a body
     * submitted under any other name never runs, so every happy-path test
     * below doubles as an assertion on the lock key.
     */
    private void lockIsWon() {
        when(jobLock.tryRunExclusive(eq(JOB_LOCK_NAME), any(Runnable.class)))
            .thenAnswer(invocation -> {
                invocation.getArgument(1, Runnable.class).run();
                return true;
            });
    }

    /**
     * A real (spied) tenant carrying a live comp grant - real so the test can
     * assert the actual plan flip, spied so per-row {@code
     * revertComplimentaryGrant()} calls are verifiable and orderable against
     * the repository write.
     */
    private Tenant spiedTenantWithCompGrant(String name, TenantPlan grantedPlan) {
        Tenant tenant = new Tenant(
            name, TenantPlan.FREE, name, "agentic-ai",
            AccountType.B2C, ExperienceMode.ADVANCED
        );
        Instant grantedAt = Instant.now().minus(Duration.ofDays(30));
        // Expiry in the past - these are exactly the rows the derived query
        // would return in production.
        tenant.grantComplimentaryPlan(grantedPlan, grantedAt, Instant.now().minus(Duration.ofHours(2)), "academy-fast-track-scholarship");
        return spy(tenant);
    }

    @Test
    void lockNotWonMeansTheRepositoryIsNeverTouchedAtAll() {
        // Another replica holds the lock: tryRunExclusive reports false
        // WITHOUT invoking the body (Mockito's default for an unstubbed
        // boolean method, made explicit here for readability).
        when(jobLock.tryRunExclusive(eq(JOB_LOCK_NAME), any(Runnable.class))).thenReturn(false);

        reverter.revertExpiredCompGrants();

        // Not even the read may run outside the lock - the lock's stated
        // purpose is sparing losing replicas the redundant scan, and a body
        // that reads-then-writes outside it would silently reintroduce the
        // two-sessions-flushing-the-same-rows race the javadoc rules out.
        verifyNoInteractions(tenantRepository);
        verify(jobLock).tryRunExclusive(eq(JOB_LOCK_NAME), any(Runnable.class));
    }

    @Test
    void everyExpiredTenantAndNoOtherIsRevertedThenPersistedInASingleSaveAll() {
        lockIsWon();
        Tenant scholarship = spiedTenantWithCompGrant("acme", TenantPlan.PREMIUM);
        Tenant promo = spiedTenantWithCompGrant("globex", TenantPlan.UNLIMITED);
        when(tenantRepository.findByCompPlanExpiresAtBefore(any(Instant.class)))
            .thenReturn(List.of(scholarship, promo));

        reverter.revertExpiredCompGrants();

        // Exactly one revert per expired row - a second call would be
        // harmless (idempotent), but zero calls is the UNLIMITED-leaks-
        // forever regression this suite exists to catch.
        verify(scholarship, times(1)).revertComplimentaryGrant();
        verify(promo, times(1)).revertComplimentaryGrant();

        // One saveAll, carrying exactly the rows the expiry query returned -
        // any extra tenant in this collection would be the mid-term-downgrade
        // regression (the reverter has no source of tenants other than the
        // query, so the persisted set IS the reverted set).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tenant>> saved = ArgumentCaptor.forClass(List.class);
        verify(tenantRepository, times(1)).saveAll(saved.capture());
        assertEquals(2, saved.getValue().size());
        assertSame(scholarship, saved.getValue().get(0));
        assertSame(promo, saved.getValue().get(1));

        // End-to-end through the real entity: the comp'd plan is actually
        // gone and the bookkeeping cleared, not just a method "verified".
        assertEquals(TenantPlan.FREE, scholarship.getPlan());
        assertEquals(TenantPlan.FREE, promo.getPlan());
        assertNull(promo.getCompPlanExpiresAt());
        assertNull(promo.getCompPlanReason());
    }

    @Test
    void tenantsAreMutatedBeforeTheSaveAllNotAfter() {
        // Save-then-mutate would persist the still-comp'd state and rely on
        // JPA dirty-checking to quietly paper over the bug - which stops
        // papering the moment saveAll's result (detached copies under some
        // configurations) is what gets flushed. Pin the order explicitly.
        lockIsWon();
        Tenant expired = spiedTenantWithCompGrant("acme", TenantPlan.UNLIMITED);
        when(tenantRepository.findByCompPlanExpiresAtBefore(any(Instant.class)))
            .thenReturn(List.of(expired));

        reverter.revertExpiredCompGrants();

        InOrder inOrder = inOrder(expired, tenantRepository);
        inOrder.verify(expired).revertComplimentaryGrant();
        inOrder.verify(tenantRepository).saveAll(anyList());
    }

    @Test
    void nothingExpiredShortCircuitsWithoutAnyWrite() {
        lockIsWon();
        when(tenantRepository.findByCompPlanExpiresAtBefore(any(Instant.class)))
            .thenReturn(List.of());

        reverter.revertExpiredCompGrants();

        // The no-op-on-every-deployment-without-grants path: an empty-list
        // saveAll would be harmless today but turns every hourly tick into a
        // pointless write path - and the short-circuit is the documented
        // contract, so its loss should fail a test, not slip by.
        verify(tenantRepository, never()).saveAll(anyList());
    }

    @Test
    void theExpiryCutoffIsTheCurrentInstantNotAFutureOrPastOne() {
        // The unexpired-downgrade regression lives in this argument: a cutoff
        // even slightly in the future sweeps grants still mid-term (and via
        // the site-wide promo, does so for every tenant at once), while a
        // stale past cutoff leaves just-expired grants leaking until some
        // later tick. Bound the captured instant by the test's own clock.
        lockIsWon();
        when(tenantRepository.findByCompPlanExpiresAtBefore(any(Instant.class)))
            .thenReturn(List.of());
        Instant before = Instant.now();

        reverter.revertExpiredCompGrants();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(tenantRepository).findByCompPlanExpiresAtBefore(cutoff.capture());
        assertFalse(cutoff.getValue().isBefore(before),
            "cutoff must not lag the tick (leaks expired grants for another hour)");
        assertFalse(cutoff.getValue().isAfter(after),
            "cutoff must not lead the tick (downgrades unexpired grants mid-term)");
    }

    @Test
    void aTenantGrantedFromAPaidPlanIsRestoredToThatPlanNotHammeredToFree() {
        // The site-wide promo grants UNLIMITED to ALREADY-PAYING tenants too;
        // reverting one of those to FREE (instead of its pre-promo PREMIUM)
        // would cancel a paid customer's entitlements the hour the promo
        // ends. TenantTest pins this on the entity; this test pins that the
        // reverter reaches that path through revertComplimentaryGrant()
        // rather than, say, a blunt changePlan(FREE).
        lockIsWon();
        Tenant paying = new Tenant(
            "initech", TenantPlan.PREMIUM, "initech", "agentic-ai",
            AccountType.B2B, ExperienceMode.ADVANCED
        );
        paying.grantComplimentaryPlan(
            TenantPlan.UNLIMITED,
            Instant.now().minus(Duration.ofDays(14)),
            Instant.now().minus(Duration.ofHours(1)),
            "site-wide-2-week-unlimited-promo"
        );
        when(tenantRepository.findByCompPlanExpiresAtBefore(any(Instant.class)))
            .thenReturn(List.of(paying));

        reverter.revertExpiredCompGrants();

        assertEquals(TenantPlan.PREMIUM, paying.getPlan());
        assertNull(paying.getCompPlanExpiresAt());
    }

    @Test
    void theLockReportsTheBodyRanRegardlessOfWhetherAnythingWasExpired() {
        // Belt-and-braces on the wiring itself: the scheduled entrypoint must
        // delegate to tryRunExclusive and nothing else - a future refactor
        // that "simplifies" the entrypoint into calling the body directly
        // would pass every other happy-path test here (they force the lock
        // open) while silently dropping cross-replica exclusion.
        when(jobLock.tryRunExclusive(eq(JOB_LOCK_NAME), any(Runnable.class))).thenReturn(true);

        reverter.revertExpiredCompGrants();

        verify(jobLock).tryRunExclusive(eq(JOB_LOCK_NAME), any(Runnable.class));
        // The lock was stubbed NOT to invoke the body, so any repository
        // traffic here proves work escaped the lock wrapper.
        verifyNoInteractions(tenantRepository);
    }

    // --- "your plan has ended" mail --------------------------------------------------------------------------

    @Test
    void aTenantThatLandsOnFreeIsToldItsPlanHasEnded() {
        lockIsWon();
        com.agenticai.core.billing.email.BillingEmailService mail = mock(com.agenticai.core.billing.email.BillingEmailService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(reverter, "billingEmailService", mail);
        Tenant free = spiedTenantWithCompGrant("acme", TenantPlan.UNLIMITED);
        when(tenantRepository.findByCompPlanExpiresAtBefore(any(Instant.class))).thenReturn(List.of(free));

        reverter.revertExpiredCompGrants();

        verify(mail).sendCompGrantEndedEmail(any(), eq("acme"));
    }

    @Test
    void aTenantReturningToAPaidPlanIsNotToldToSubscribe() {
        lockIsWon();
        com.agenticai.core.billing.email.BillingEmailService mail = mock(com.agenticai.core.billing.email.BillingEmailService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(reverter, "billingEmailService", mail);
        Tenant payer = new Tenant("payer", TenantPlan.PREMIUM, "payer", "agentic-ai", AccountType.B2C, ExperienceMode.ADVANCED);
        payer.grantComplimentaryPlan(TenantPlan.UNLIMITED, Instant.now().minus(Duration.ofDays(30)), Instant.now().minus(Duration.ofHours(1)), "promo");
        when(tenantRepository.findByCompPlanExpiresAtBefore(any(Instant.class))).thenReturn(List.of(payer));

        reverter.revertExpiredCompGrants();

        verify(mail, never()).sendCompGrantEndedEmail(any(), any());
    }
}
