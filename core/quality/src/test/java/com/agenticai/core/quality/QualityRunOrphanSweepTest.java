package com.agenticai.core.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.dataset.EvalRunRepository;
import com.agenticai.core.quality.simulation.SimulationRunRepository;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QualityRunOrphanSweepTest {

    private final EvalRunRepository evalRuns = mock(EvalRunRepository.class);
    private final SimulationRunRepository simulationRuns = mock(SimulationRunRepository.class);
    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
    private final PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);

    @BeforeEach
    void iteratorRunsTheActionForOneTenant() {
        doAnswer(call -> {
            call.<Consumer<String>>getArgument(0).accept("tenant_a");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
    }

    private QualityRunOrphanSweep sweep(String mode, String minAge) {
        return new QualityRunOrphanSweep(evalRuns, simulationRuns, iterator, lock, mode, minAge);
    }

    @Test
    void defaultModeIsANoOpSoTodaysBehaviourIsUnchanged() {
        sweep("all", "PT2H").sweepStaleRuns();

        verifyNoInteractions(lock);
        verifyNoInteractions(evalRuns);
        verifyNoInteractions(simulationRuns);
    }

    @Test
    void staleModeRunsUnderTheAdvisoryLock() {
        sweep("stale", "PT2H").sweepStaleRuns();

        verify(lock).tryRunExclusive(eq(QualityRunOrphanSweep.LOCK_NAME), any(Runnable.class));
        // The mocked lock never runs the job, so the replica that lost the lock sweeps nothing.
        verify(iterator, never()).forEachActiveTenantSchema(any());
    }

    @Test
    void offModeAlsoRunsThePeriodicStaleSweep() {
        sweep("off", "PT2H").sweepStaleRuns();

        verify(lock).tryRunExclusive(eq(QualityRunOrphanSweep.LOCK_NAME), any(Runnable.class));
    }

    @Test
    void sweepFailsOnlyRowsOlderThanTheMinimumAgeInEveryTenant() {
        Instant before = Instant.now();

        sweep("stale", "PT30M").sweepAllTenants();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> evalCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> simCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(evalRuns).failStaleLiveRuns(eq(BootOrphanSweepPolicy.STALE_REASON), any(Instant.class), evalCutoff.capture());
        verify(simulationRuns).failStaleLiveRuns(eq(BootOrphanSweepPolicy.STALE_REASON), any(Instant.class), simCutoff.capture());
        assertThat(evalCutoff.getValue()).isBetween(before.minusSeconds(30 * 60), after.minusSeconds(30 * 60));
        assertThat(simCutoff.getValue()).isEqualTo(evalCutoff.getValue());
    }

    @Test
    void aFailingEvalSweepDoesNotStopTheSimulationSweep() {
        when(evalRuns.failStaleLiveRuns(anyString(), any(Instant.class), any(Instant.class)))
            .thenThrow(new IllegalStateException("db down"));

        sweep("stale", "PT2H").sweepAllTenants();

        verify(simulationRuns).failStaleLiveRuns(anyString(), any(Instant.class), any(Instant.class));
    }
}
