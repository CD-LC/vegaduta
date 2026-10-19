package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantSchemaIterator} is the one utility standing between every tenant-scoped
 * {@code @Scheduled} job and the third occurrence of this project's twice-fixed cross-tenant bug
 * class (the knowledge-base leak, the admin-users leak - see the iterator's own javadoc). Its
 * entire value is a behaviour no repository-level {@code verify} can see: that {@link
 * TenantContext} is bound to exactly the right tenant WHILE the action runs, and cleared no matter
 * how the action ends. These tests therefore assert the context from INSIDE the consumer - the
 * vantage point a real scheduled job's repository call actually has - rather than only inspecting
 * mock interactions after the fact. A regression here (context leaking from tenant N into tenant
 * N+1's iteration, or surviving onto a reused thread-pool thread) would be a silent cross-tenant
 * data leak, not a test failure anywhere else in the suite.
 *
 * <p>Real (not mocked) {@link TenantContext} static holder, with an {@code @AfterEach} clear -
 * exactly the pattern {@link TenantSchemaResolverTest} establishes for the same reason: the
 * ThreadLocal IS the behaviour under test, and mocking it would test nothing real.
 */
class TenantSchemaIteratorTest {

    private TenantRepository tenantRepository;
    private TenantSchemaIterator iterator;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        iterator = new TenantSchemaIterator(tenantRepository);
    }

    @AfterEach
    void clearPerThreadState() {
        // Same guard TenantSchemaResolverTest uses - nothing this class does
        // may leak a TenantContext into the next test on the same fork.
        TenantContext.clear();
    }

    private static Tenant activeTenant(String name, String schema) {
        return new Tenant(
                name,
                TenantPlan.FREE,
                schema,
                "agentic-ai",
                AccountType.B2C,
                ExperienceMode.SIMPLE);
    }

    @Test
    void iteratesOnlyActiveTenantsAndNothingElseFromTheRepository() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a"), activeTenant("B", "tenant_b")));

        List<String> visitedSchemas = new ArrayList<>();
        iterator.forEachActiveTenantSchema(visitedSchemas::add);

        assertEquals(List.of("tenant_a", "tenant_b"), visitedSchemas);
        // The ACTIVE filter is the whole point of the query - a PROVISIONING,
        // PROVISIONING_FAILED or ERASED tenant has no (usable) schema to run
        // a job against, and an implementation that switched to findAll()
        // would start touching half-provisioned and erased schemas.
        verify(tenantRepository).findByStatus(TenantStatus.ACTIVE);
        verifyNoMoreInteractions(tenantRepository);
    }

    @Test
    void tenantContextIsBoundToExactlyTheCurrentTenantsSchemaForTheDurationOfEachAction() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a"), activeTenant("B", "tenant_b")));

        // Captured from INSIDE the consumer - the vantage point a scheduled
        // job's JPA repository call actually resolves its schema from. This
        // is the assertion that catches a set-after-accept or set-once-for-
        // the-whole-batch regression, which a verify() on the repository mock
        // never could.
        List<String> boundTenantIds = new ArrayList<>();
        List<String> boundSubjects = new ArrayList<>();
        iterator.forEachActiveTenantSchema(
                schema -> {
                    TenantContext bound =
                            TenantContext.current()
                                    .orElseThrow(
                                            () ->
                                                    new AssertionError(
                                                            "action ran with NO TenantContext bound"
                                                                + " - the exact"
                                                                + " silent-empty-public-schema bug"
                                                                + " this iterator exists to"
                                                                + " prevent"));
                    boundTenantIds.add(bound.getTenantId());
                    boundSubjects.add(bound.getSubject());
                    // The schema handed to the action and the schema bound on the
                    // thread must be the same tenant - never off-by-one-iteration.
                    assertEquals(schema, bound.getTenantId());
                });

        assertEquals(List.of("tenant_a", "tenant_b"), boundTenantIds);
        // The synthetic system actor, not a leaked human principal - what any
        // audit write inside the action would record as the actor.
        assertEquals(List.of("system:scheduled-job", "system:scheduled-job"), boundSubjects);
    }

    @Test
    void contextIsClearedAfterTheIterationSoAReusedThreadNeverInheritsTheLastTenant() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));

        iterator.forEachActiveTenantSchema(schema -> {});

        // A @Scheduled thread is pool-reused; if tenant_a's context survived
        // here, some unrelated later task on this thread would silently run
        // as tenant_a.
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void contextIsClearedInTheFinallyEvenWhenTheActionThrows() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));

        assertDoesNotThrow(
                () ->
                        iterator.forEachActiveTenantSchema(
                                schema -> {
                                    throw new RuntimeException("job blew up for this tenant");
                                }));

        // The finally must have run - an implementation that only cleared on
        // the happy path would leave tenant_a bound on this thread forever.
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void oneTenantsRuntimeExceptionIsSwallowedAndTheRemainingTenantsStillRunWithTheirOwnContext() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a"), activeTenant("B", "tenant_b")));

        List<String> boundDuringSurvivors = new ArrayList<>();
        assertDoesNotThrow(
                () ->
                        iterator.forEachActiveTenantSchema(
                                schema -> {
                                    if ("tenant_a".equals(schema)) {
                                        throw new RuntimeException("one bad row in tenant_a");
                                    }
                                    // The assertion that matters most: iteration N+1 must start
                                    // with
                                    // ITS OWN context, not tenant N's leftover one from before the
                                    // throw - the throw happens between set() and clear(), so a
                                    // missing finally would leave tenant_a bound right here.
                                    boundDuringSurvivors.add(
                                            TenantContext.current().orElseThrow().getTenantId());
                                }));

        assertEquals(List.of("tenant_b"), boundDuringSurvivors);
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void characterizationTheFinallyClearsRatherThanRestoresAPreExistingCallerContext() {
        // CHARACTERIZATION, not aspiration: the finally runs clear(), not a
        // save-and-restore of whatever context the calling thread already
        // had. Benign today - only @Scheduled threads (which by construction
        // have no context) call this - but load-bearing: the day a request-
        // scoped caller uses this helper, its own tenant context is silently
        // destroyed on return and the rest of that request runs contextless
        // (public-schema fallback). If this test starts failing because
        // someone made the finally restore instead, that is an intentional
        // semantic upgrade - update this test; if it fails any other way,
        // something is wrong.
        TenantContext.set(TenantContext.of("request_tenant", "human-user", Set.of()));
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));

        iterator.forEachActiveTenantSchema(schema -> {});

        assertTrue(
                TenantContext.current().isEmpty(),
                "forEachActiveTenantSchema now RESTORES a pre-existing caller context instead of"
                        + " clearing it - deliberate behaviour change? Update this characterization"
                        + " test to match.");
    }

    @Test
    void nonDemoVariantUsesTheDemoExcludingQueryAndStillBindsAndClearsContext() {
        when(tenantRepository.findActiveExcludingDemo())
                .thenReturn(List.of(activeTenant("A", "tenant_a"), activeTenant("B", "tenant_b")));

        List<String> boundDuringAction = new ArrayList<>();
        iterator.forEachActiveNonDemoTenantSchema(
                schema ->
                        boundDuringAction.add(
                                TenantContext.current()
                                        .map(TenantContext::getTenantId)
                                        .orElse(null)));

        assertEquals(List.of("tenant_a", "tenant_b"), boundDuringAction);
        verify(tenantRepository).findActiveExcludingDemo();
        verifyNoMoreInteractions(tenantRepository);
        assertTrue(TenantContext.current().isEmpty());
    }

    // ---- idle back-off ----

    private long[] now = {0L};

    private TenantSchemaIterator idleIterator(boolean enabled, int maxTicks, long maxMillis) {
        return new TenantSchemaIterator(
                tenantRepository, enabled, maxTicks, maxMillis, () -> now[0]);
    }

    private int runTicks(
            TenantSchemaIterator it, int ticks, java.util.function.Predicate<String> work) {
        int[] visits = {0};
        for (int i = 0; i < ticks; i++) {
            it.forEachActiveTenantSchemaSkippingIdle(
                    "job",
                    schema -> {
                        visits[0]++;
                        return work.test(schema);
                    });
            now[0] += 10_000L;
        }
        return visits[0];
    }

    @Test
    void idleSkipDisabledVisitsEveryTenantEveryTick() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));
        assertEquals(8, runTicks(idleIterator(false, 3, 60_000), 8, s -> false));
    }

    @Test
    void idleTenantBacksOffButIsNeverSkippedLongerThanTheCap() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));
        // ticks: V S V S S V S S S V S S S V => 5 visits over 14 ticks
        assertEquals(5, runTicks(idleIterator(true, 3, 600_000), 14, s -> false));
    }

    @Test
    void freshWorkResetsToEveryTick() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));
        TenantSchemaIterator it = idleIterator(true, 3, 600_000);
        assertEquals(2, runTicks(it, 4, s -> false)); // V S V S -> mid-skip (1 skip left)
        // S (skip exhausted), V finds work, V: exactly 2 visits
        assertEquals(2, runTicks(it, 3, s -> true));
        // reset to every-tick: all 4 visited
        assertEquals(4, runTicks(it, 4, s -> true));
    }

    @Test
    void exceptionCountsAsWorkAndIsRetriedEveryTick() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));
        TenantSchemaIterator it = idleIterator(true, 3, 600_000);
        assertEquals(2, runTicks(it, 4, s -> false)); // V S V S -> mid-skip
        assertEquals(0, runTicks(it, 1, s -> false)); // S (last skip)
        // each tick visits (and throws); if a throw were treated as idle it would back off
        assertEquals(
                3,
                runTicks(
                        it,
                        3,
                        s -> {
                            throw new IllegalStateException("boom");
                        }));
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void durationCapForcesAVisitEvenWithSkipsRemaining() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));
        TenantSchemaIterator it = idleIterator(true, 8, 600_000);
        // V S V S S V -> 3 idle visits, streak 3, 4 skips pending
        assertEquals(3, runTicks(it, 6, s -> false));
        // within the cap the tenant is still skipped
        assertEquals(0, runTicks(it, 1, s -> false));
        // stale memo: last visit is now older than the cap, so it is visited despite skips
        // remaining
        now[0] += 700_000L;
        assertEquals(1, runTicks(it, 1, s -> false));
    }

    @Test
    void removedTenantsAreForgottenAndContextIsBoundDuringIdleAwareAction() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a"), activeTenant("B", "tenant_b")));
        TenantSchemaIterator it = idleIterator(true, 3, 600_000);
        List<String> seen = new ArrayList<>();
        it.forEachActiveTenantSchemaSkippingIdle(
                "job",
                schema -> {
                    seen.add(schema + ":" + TenantContext.current().orElseThrow().getTenantId());
                    return false;
                });
        assertEquals(List.of("tenant_a:tenant_a", "tenant_b:tenant_b"), seen);
        assertTrue(TenantContext.current().isEmpty());
        // tenant_b removed, then re-added: must be visited immediately (memo pruned)
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));
        it.forEachActiveTenantSchemaSkippingIdle("job", s -> false);
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a"), activeTenant("B", "tenant_b")));
        List<String> again = new ArrayList<>();
        it.forEachActiveTenantSchemaSkippingIdle(
                "job",
                schema -> {
                    again.add(schema);
                    return false;
                });
        assertTrue(again.contains("tenant_b"));
    }

    // ---- starvation guard ----

    /**
     * The no-starvation contract of idle skipping, checked exhaustively rather than by example: for
     * every cap setting and every length of idle history (so every position in the back-off
     * ladder), work that appears for a skipped tenant is picked up after at most {@code
     * min(maxTicks, ceil(maxMillis / interval) - 1)} skipped ticks. This is the staleness bound the
     * docs publish; if a change lets a tenant be skipped longer, this test fails before a queued
     * agent job or webhook delivery silently waits.
     */
    @Test
    void newWorkIsPickedUpWithinTheDocumentedStalenessBoundAtEveryPointOfTheBackOffLadder() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));
        long interval = 10_000L;
        for (int maxTicks = 1; maxTicks <= 6; maxTicks++) {
            for (long maxMillis : new long[] {15_000L, 30_000L, 60_000L, 600_000L}) {
                int bound = (int) Math.min(maxTicks, (maxMillis + interval - 1) / interval - 1);
                for (int idleTicks = 0; idleTicks <= 70; idleTicks++) {
                    now[0] = 0L;
                    TenantSchemaIterator it = idleIterator(true, maxTicks, maxMillis);
                    runTicks(it, idleTicks, s -> false);
                    int skipped = 0;
                    boolean[] picked = {false};
                    while (!picked[0]) {
                        it.forEachActiveTenantSchemaSkippingIdle(
                                "job",
                                schema -> {
                                    picked[0] = true;
                                    return true;
                                });
                        now[0] += interval;
                        if (!picked[0]) {
                            skipped++;
                        }
                        assertTrue(
                                skipped <= bound,
                                "maxTicks="
                                        + maxTicks
                                        + " maxMillis="
                                        + maxMillis
                                        + " idleTicks="
                                        + idleTicks
                                        + " skipped="
                                        + skipped
                                        + " > bound "
                                        + bound);
                    }
                }
            }
        }
    }

    @Test
    void aBusyTenantIsNeverDelayedByAnIdleNeighbourOrAnotherJobsMemo() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a"), activeTenant("B", "tenant_b")));
        TenantSchemaIterator it = idleIterator(true, 3, 600_000);
        int[] busyVisits = {0};
        int[] idleVisits = {0};
        for (int i = 0; i < 20; i++) {
            it.forEachActiveTenantSchemaSkippingIdle(
                    "job",
                    schema -> {
                        if (schema.equals("tenant_b")) {
                            busyVisits[0]++;
                            return true;
                        }
                        idleVisits[0]++;
                        return false;
                    });
            // a different job sees the same tenants with its own, independent memo
            it.forEachActiveTenantSchemaSkippingIdle("other-job", s -> true);
            now[0] += 10_000L;
        }
        assertEquals(20, busyVisits[0]);
        assertTrue(idleVisits[0] < 20, "the idle tenant must actually back off");
        assertTrue(idleVisits[0] >= 20 / 4, "and must still be visited at least every 4th tick");
    }

    @Test
    void visitAndSkipCountersMatchWhatTheSweepActuallyDid() {
        when(tenantRepository.findByStatus(TenantStatus.ACTIVE))
                .thenReturn(List.of(activeTenant("A", "tenant_a")));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        TenantSchemaIterator it = idleIterator(true, 3, 600_000);
        it.setMeterRegistry(registry);
        int visits = runTicks(it, 14, s -> false); // V S V S S V S S S V S S S V
        double visited =
                registry.counter(
                                "vegaduta.tenant.sweep.tenants", "job", "job", "outcome", "visited")
                        .count();
        double skipped =
                registry.counter(
                                "vegaduta.tenant.sweep.tenants", "job", "job", "outcome", "skipped")
                        .count();
        assertEquals(visits, (int) visited);
        assertEquals(14 - visits, (int) skipped);
    }

    // ---- Spring property binding ----

    private org.springframework.boot.test.context.runner.ApplicationContextRunner contextRunner() {
        return new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                // SpringApplication installs this on the real app's bean factory; the bare runner
                // context lacks it
                .withInitializer(
                        c ->
                                c.getBeanFactory()
                                        .setConversionService(
                                                org.springframework.boot.convert
                                                        .ApplicationConversionService
                                                        .getSharedInstance()))
                .withBean(TenantRepository.class, () -> tenantRepository)
                .withUserConfiguration(TenantSchemaIterator.class);
    }

    @Test
    void unsetPropertiesBindSafeDefaultsOff() {
        contextRunner()
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            TenantSchemaIterator it = ctx.getBean(TenantSchemaIterator.class);
                            assertEquals(
                                    false,
                                    org.springframework.test.util.ReflectionTestUtils.getField(
                                            it, "idleSkipEnabled"));
                            assertEquals(
                                    3,
                                    org.springframework.test.util.ReflectionTestUtils.getField(
                                            it, "idleSkipMaxTicks"));
                            assertEquals(
                                    60_000L,
                                    org.springframework.test.util.ReflectionTestUtils.getField(
                                            it, "idleSkipMaxMillis"));
                        });
    }

    @Test
    void idleSkipPropertiesBindIncludingDurationString() {
        contextRunner()
                .withPropertyValues(
                        "app.tenant.idle-skip.enabled=true",
                        "app.tenant.idle-skip.max-skipped-ticks=5",
                        "app.tenant.idle-skip.max-skip-duration=90s")
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            TenantSchemaIterator it = ctx.getBean(TenantSchemaIterator.class);
                            assertEquals(
                                    true,
                                    org.springframework.test.util.ReflectionTestUtils.getField(
                                            it, "idleSkipEnabled"));
                            assertEquals(
                                    5,
                                    org.springframework.test.util.ReflectionTestUtils.getField(
                                            it, "idleSkipMaxTicks"));
                            assertEquals(
                                    90_000L,
                                    org.springframework.test.util.ReflectionTestUtils.getField(
                                            it, "idleSkipMaxMillis"));
                        });
    }
}
