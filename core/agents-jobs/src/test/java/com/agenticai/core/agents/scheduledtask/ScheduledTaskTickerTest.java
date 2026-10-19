package com.agenticai.core.agents.scheduledtask;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ticks: flag off is an identity (nothing is locked, iterated or read), flag on runs every tenant under the
 * advisory lock and one tenant's failure never stops the next.
 */
class ScheduledTaskTickerTest {

    private TenantSchemaIterator iterator;
    private ScheduledTaskRunner runner;
    private PgAdvisoryJobLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        iterator = mock(TenantSchemaIterator.class);
        runner = mock(ScheduledTaskRunner.class);
        lock = mock(PgAdvisoryJobLock.class);
        doAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return true;
        }).when(lock).tryRunExclusive(anyString(), any(Runnable.class));
        doAnswer(i -> {
            Consumer<String> action = i.getArgument(0);
            action.accept("tenant_a");
            action.accept("tenant_b");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
    }

    private static ScheduledTaskSettings settings(boolean enabled) {
        return new ScheduledTaskSettings(enabled, true, 10, 3, 15, 3, 2000, 720, true, 3500, "in_app");
    }

    @Test
    void withTheFlagOffNeitherTickTouchesTheLockTheTenantsOrTheRunner() {
        ScheduledTaskTicker ticker = new ScheduledTaskTicker(iterator, runner, lock, settings(false));

        ticker.fireDueTasks();
        ticker.settleFinishedRuns();

        verifyNoInteractions(iterator, runner, lock);
    }

    @Test
    void withTheFlagOnTheFireTickRunsEveryTenantUnderItsOwnLock() {
        ScheduledTaskTicker ticker = new ScheduledTaskTicker(iterator, runner, lock, settings(true));

        ticker.fireDueTasks();

        verify(lock).tryRunExclusive(anyString(), any(Runnable.class));
        verify(runner, times(2)).fireDueTasks(any(Instant.class));
        verify(runner, never()).settleFinishedRuns(any(Instant.class));
    }

    @Test
    void withTheFlagOnTheSettleTickRunsEveryTenantAndUsesADifferentLock() {
        ScheduledTaskTicker ticker = new ScheduledTaskTicker(iterator, runner, lock, settings(true));
        org.mockito.ArgumentCaptor<String> names = org.mockito.ArgumentCaptor.forClass(String.class);

        ticker.fireDueTasks();
        ticker.settleFinishedRuns();

        verify(lock, times(2)).tryRunExclusive(names.capture(), any(Runnable.class));
        org.junit.jupiter.api.Assertions.assertNotEquals(names.getAllValues().get(0), names.getAllValues().get(1));
        verify(runner, times(2)).settleFinishedRuns(any(Instant.class));
    }

    @Test
    void oneTenantFailingNeverStopsTheNextTenant() {
        ScheduledTaskTicker ticker = new ScheduledTaskTicker(iterator, runner, lock, settings(true));
        doThrow(new IllegalStateException("tenant_a is broken")).doNothing().when(runner).fireDueTasks(any(Instant.class));

        ticker.fireDueTasks();

        verify(runner, times(2)).fireDueTasks(any(Instant.class));
    }

    @Test
    void bothTicksShareOneInstantAcrossTenantsWithinATick() {
        ScheduledTaskTicker ticker = new ScheduledTaskTicker(iterator, runner, lock, settings(true));
        org.mockito.ArgumentCaptor<Instant> nows = org.mockito.ArgumentCaptor.forClass(Instant.class);

        ticker.fireDueTasks();

        verify(runner, times(2)).fireDueTasks(nows.capture());
        org.junit.jupiter.api.Assertions.assertEquals(nows.getAllValues().get(0), nows.getAllValues().get(1));
    }
}
