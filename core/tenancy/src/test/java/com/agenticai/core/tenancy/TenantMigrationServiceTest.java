package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Parallel boot migration (2026-09-24): every ACTIVE schema is still attempted exactly once, one
 * bad tenant still never blocks the rest, and work really does overlap up to the configured
 * parallelism.
 */
class TenantMigrationServiceTest {

    private static Tenant tenant(String schema) {
        Tenant t = mock(Tenant.class);
        when(t.getSchemaName()).thenReturn(schema);
        when(t.getName()).thenReturn(schema + "-name");
        return t;
    }

    private static TenantRepository repoWith(String... schemas) {
        TenantRepository repo = mock(TenantRepository.class);
        List<Tenant> tenants = new ArrayList<>();
        for (String s : schemas) {
            tenants.add(tenant(s));
        }
        when(repo.findByStatus(TenantStatus.ACTIVE)).thenReturn(tenants);
        return repo;
    }

    @Test
    void migratesEverySchemaOnceAndOverlapsUpToTheParallelism() throws Exception {
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        doAnswer(
                        inv -> {
                            int now = inFlight.incrementAndGet();
                            peak.accumulateAndGet(now, Math::max);
                            seen.add(inv.getArgument(0));
                            Thread.sleep(40);
                            inFlight.decrementAndGet();
                            return null;
                        })
                .when(migrator)
                .migrate(anyString());

        TenantMigrationService service =
                new TenantMigrationService(repoWith("a", "b", "c", "d", "e", "f"), migrator, 3);

        List<String> failed = service.migrateAllActiveTenants();

        assertTrue(failed.isEmpty(), "no failures expected");
        assertEquals(6, seen.size());
        assertEquals(Set.of("a", "b", "c", "d", "e", "f"), Set.copyOf(seen));
        assertTrue(peak.get() >= 2, "expected overlapping migrations, peak was " + peak.get());
        assertTrue(
                peak.get() <= 3,
                "must never exceed the configured parallelism, peak was " + peak.get());
    }

    @Test
    void oneFailingTenantIsReportedAndDoesNotStopTheOthers() {
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        doAnswer(
                        inv -> {
                            if ("bad".equals(inv.getArgument(0))) {
                                throw new IllegalStateException("boom");
                            }
                            return null;
                        })
                .when(migrator)
                .migrate(anyString());

        TenantMigrationService service =
                new TenantMigrationService(repoWith("ok1", "bad", "ok2"), migrator, 4);

        List<String> failed = service.migrateAllActiveTenants();

        assertEquals(List.of("bad"), failed);
        verify(migrator, times(3)).migrate(anyString());
    }

    @Test
    void failedSchemaIsRecordedAndClearedByASuccessfulRetry() {
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        java.util.concurrent.atomic.AtomicBoolean broken =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(
                        inv -> {
                            if ("bad".equals(inv.getArgument(0)) && broken.get()) {
                                throw new IllegalStateException("boom");
                            }
                            return null;
                        })
                .when(migrator)
                .migrate(anyString());
        TenantMigrationService service =
                new TenantMigrationService(repoWith("ok1", "bad"), migrator, 2);
        TenantMigrationFailures failures = new TenantMigrationFailures();
        service.setFailures(failures);

        service.migrateAllActiveTenants();
        assertTrue(failures.hasFailed("bad"));
        assertTrue(!failures.hasFailed("ok1"));
        assertEquals(List.of("bad"), service.retryFailed());
        assertTrue(failures.hasFailed("bad"));

        broken.set(false);
        assertTrue(service.retryFailed().isEmpty());
        assertTrue(!failures.hasFailed("bad"));
    }

    @Test
    void parallelismBelowOneStillRunsSequentially() {
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        TenantMigrationService service =
                new TenantMigrationService(repoWith("x", "y"), migrator, 0);

        assertTrue(service.migrateAllActiveTenants().isEmpty());
        verify(migrator, times(2)).migrate(anyString());
    }

    @Test
    void noActiveTenantsIsANoOp() {
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        TenantRepository repo = mock(TenantRepository.class);
        when(repo.findByStatus(TenantStatus.ACTIVE)).thenReturn(List.of());

        assertTrue(
                new TenantMigrationService(repo, migrator, 4).migrateAllActiveTenants().isEmpty());
        verify(migrator, times(0)).migrate(anyString());
    }

    @Test
    void parallelismIsCappedToWhatTheConnectionPoolCanCarry() {
        // 2 reserved (advisory-lock connection + lookups) + 2 per Flyway run.
        assertEquals(4, TenantMigrationService.boundedParallelism(4, 10));
        assertEquals(
                3,
                TenantMigrationService.boundedParallelism(4, 8),
                "the old migrate Job pool of 8");
        assertEquals(1, TenantMigrationService.boundedParallelism(4, 3));
        assertEquals(1, TenantMigrationService.boundedParallelism(4, 1));
        assertEquals(
                2,
                TenantMigrationService.boundedParallelism(2, 15),
                "never raised above the request");
        assertEquals(1, TenantMigrationService.boundedParallelism(0, 15));
    }

    @Test
    void aSmallPoolReallyLimitsHowManySchemasMigrateAtOnce() {
        TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        doAnswer(
                        inv -> {
                            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                            Thread.sleep(40);
                            inFlight.decrementAndGet();
                            return null;
                        })
                .when(migrator)
                .migrate(anyString());

        TenantMigrationService service =
                new TenantMigrationService(
                        repoWith("a", "b", "c", "d", "e", "f", "g", "h"), migrator, 4, 6);

        assertTrue(service.migrateAllActiveTenants().isEmpty());
        assertTrue(peak.get() <= 2, "pool of 6 carries 2 Flyway runs, peak was " + peak.get());
    }
}
