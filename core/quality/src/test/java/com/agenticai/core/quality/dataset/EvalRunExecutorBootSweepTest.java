package com.agenticai.core.quality.dataset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The eval boot sweep: default fails every live row (today); stale/off never fail a possibly-live run. */
class EvalRunExecutorBootSweepTest {

    private final EvalRunRepository runs = mock(EvalRunRepository.class);
    private final TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
    private EvalRunExecutor executor;

    @BeforeEach
    void setUp() {
        doAnswer(call -> {
            call.<Consumer<String>>getArgument(0).accept("tenant_a");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
        executor = new EvalRunExecutor(runs, iterator);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void defaultStillFailsEveryLiveRun() {
        executor.sweepOrphanedRuns();

        verify(runs).failAllLiveRuns(anyString(), any(Instant.class));
        verify(runs, never()).failStaleLiveRuns(anyString(), any(Instant.class), any(Instant.class));
    }

    @Test
    void staleModeFailsOnlyOldRows() {
        ReflectionTestUtils.setField(executor, "bootOrphanSweep", "stale");

        executor.sweepOrphanedRuns();

        verify(runs, never()).failAllLiveRuns(anyString(), any(Instant.class));
        verify(runs).failStaleLiveRuns(anyString(), any(Instant.class), any(Instant.class));
    }

    @Test
    void offModeFailsNothingAtBoot() {
        ReflectionTestUtils.setField(executor, "bootOrphanSweep", "off");

        executor.sweepOrphanedRuns();

        verify(runs, never()).failAllLiveRuns(anyString(), any(Instant.class));
        verify(runs, never()).failStaleLiveRuns(anyString(), any(Instant.class), any(Instant.class));
    }
}
