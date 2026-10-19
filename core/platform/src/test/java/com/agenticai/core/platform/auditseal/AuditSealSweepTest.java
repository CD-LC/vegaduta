package com.agenticai.core.platform.auditseal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.seal.AuditSeal;
import com.agenticai.core.audit.seal.AuditSealService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class AuditSealSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private AuditSealService sealService;
    private TenantSchemaIterator schemaIterator;
    private PgAdvisoryJobLock jobLock;

    @BeforeEach
    void setUp() {
        sealService = mock(AuditSealService.class);
        schemaIterator = mock(TenantSchemaIterator.class);
        jobLock = mock(PgAdvisoryJobLock.class);
        when(sealService.signerAvailable()).thenReturn(true);
        when(jobLock.tryRunExclusive(eq(AuditSealSweep.LOCK_NAME), any(Runnable.class))).thenAnswer(inv -> {
            Runnable job = inv.getArgument(1);
            job.run();
            return true;
        });
        doAnswer(inv -> {
            Consumer<String> action = inv.getArgument(0);
            action.accept("t_a");
            action.accept("t_b");
            return null;
        }).when(schemaIterator).forEachActiveTenantSchema(any());
    }

    private AuditSealSweep sweep(boolean enabled) {
        return new AuditSealSweep(sealService, schemaIterator, jobLock, enabled, 600, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Reads NOW for the first reads, then a moment past the 600 s tick budget. */
    private static Clock expiringClock() {
        return new Clock() {
            private int reads;

            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return reads++ < 2 ? NOW : NOW.plusSeconds(601);
            }
        };
    }

    private static AuditSeal aSeal(long seq) {
        UUID id = UUID.randomUUID();
        return new AuditSeal("t", seq, id, NOW, id, NOW, 1, "r", "p", "s", "audit-seal-v1", "j", "k", "pk", NOW);
    }

    @Test
    void aDisabledSweepDoesNothingAndNeverTakesTheLock() {
        sweep(false).tick();
        verify(jobLock, never()).tryRunExclusive(any(), any(Runnable.class));
        verify(sealService, never()).sealNextBatch(any(), any());
    }

    @Test
    void withoutASigningKeyNothingIsSealed() {
        when(sealService.signerAvailable()).thenReturn(false);
        sweep(true).tick();
        verify(sealService, never()).sealNextBatch(any(), any());
        verify(schemaIterator, never()).forEachActiveTenantSchema(any());
    }

    @Test
    void sealsEveryTenantUntilItsBacklogIsDrained() {
        when(sealService.sealNextBatch("t_a", NOW))
            .thenReturn(Optional.of(aSeal(1)), Optional.of(aSeal(2)), Optional.empty());
        when(sealService.sealNextBatch("t_b", NOW)).thenReturn(Optional.empty());

        sweep(true).tick();

        verify(sealService, times(3)).sealNextBatch("t_a", NOW);
        verify(sealService, times(1)).sealNextBatch("t_b", NOW);
    }

    @Test
    void oneTenantsBacklogIsBoundedPerTick() {
        when(sealService.sealNextBatch("t_a", NOW)).thenAnswer(inv -> Optional.of(aSeal(1)));
        when(sealService.sealNextBatch("t_b", NOW)).thenReturn(Optional.empty());

        sweep(true).tick();

        verify(sealService, times(AuditSealSweep.MAX_SEALS_PER_TENANT_PER_TICK)).sealNextBatch("t_a", NOW);
        verify(sealService, times(1)).sealNextBatch("t_b", NOW);
    }

    @Test
    void aConcurrentSealerWinningTheRaceIsNotAnError() {
        when(sealService.sealNextBatch("t_a", NOW)).thenThrow(new DataIntegrityViolationException("uq_audit_seal_seq"));
        when(sealService.sealNextBatch("t_b", NOW)).thenReturn(Optional.of(aSeal(1)), Optional.empty());

        sweep(true).tick();

        verify(sealService, times(1)).sealNextBatch("t_a", NOW);
        verify(sealService, times(2)).sealNextBatch("t_b", NOW);
    }

    @Test
    void tenantsPastTheTickTimeBudgetAreLeftForTheNextTick() {
        when(sealService.sealNextBatch("t_a", NOW)).thenReturn(Optional.empty());
        AuditSealSweep s = new AuditSealSweep(sealService, schemaIterator, jobLock, true, 600, expiringClock());

        s.tick();

        verify(sealService, times(1)).sealNextBatch("t_a", NOW);
        verify(sealService, never()).sealNextBatch(eq("t_b"), any());
    }

    @Test
    void sealTenantCountsCreatedSeals() {
        when(sealService.sealNextBatch("t_a", NOW))
            .thenReturn(Optional.of(aSeal(1)), Optional.of(aSeal(2)), Optional.empty());
        assertEquals(2, sweep(true).sealTenant("t_a", NOW));
    }
}
