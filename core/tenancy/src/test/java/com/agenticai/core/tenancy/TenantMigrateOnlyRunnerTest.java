package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * Exit-code contract of the migrate-only Job. The exit function is injected so
 * nothing here can terminate the test JVM.
 */
class TenantMigrateOnlyRunnerTest {

    private TenantMigrationService service;
    private PgAdvisoryJobLock lock;
    private AtomicInteger exitCode;
    private TenantMigrateOnlyRunner runner;

    @BeforeEach
    void setUp() {
        service = mock(TenantMigrationService.class);
        lock = mock(PgAdvisoryJobLock.class);
        exitCode = new AtomicInteger(-1);
        runner = new TenantMigrateOnlyRunner(service, lock, exitCode::set);
    }

    /** Lock acquired: run the job body, report that it ran. */
    private void lockGranted() {
        when(lock.tryRunExclusive(eq(TenantMigrateOnlyRunner.LOCK_NAME), any(Runnable.class)))
            .thenAnswer((InvocationOnMock inv) -> {
                inv.<Runnable>getArgument(1).run();
                return true;
            });
    }

    @Test
    void exitsZeroWhenAllTenantsMigrate() {
        lockGranted();
        when(service.migrateAllActiveTenants()).thenReturn(List.of());

        runner.run(null);

        assertEquals(0, exitCode.get());
    }

    @Test
    void exitsNonZeroWhenAnyTenantFails() {
        lockGranted();
        when(service.migrateAllActiveTenants()).thenReturn(List.of("tenant_a", "tenant_b"));

        runner.run(null);

        assertEquals(1, exitCode.get());
    }

    @Test
    void exitsNonZeroOnUnexpectedException() {
        lockGranted();
        when(service.migrateAllActiveTenants()).thenThrow(new IllegalStateException("db down"));

        runner.run(null);

        assertEquals(1, exitCode.get());
    }

    @Test
    void exitsTempFailWithoutMigratingWhenAnotherJobHoldsTheLock() {
        when(lock.tryRunExclusive(eq(TenantMigrateOnlyRunner.LOCK_NAME), any(Runnable.class))).thenReturn(false);

        runner.run(null);

        assertEquals(75, exitCode.get());
        verify(service, never()).migrateAllActiveTenants();
    }
}
