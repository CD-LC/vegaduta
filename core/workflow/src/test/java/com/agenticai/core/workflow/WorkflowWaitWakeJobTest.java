package com.agenticai.core.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The wake sweep is the durable timer: off by default, tenant-iterated, lock-optional. */
class WorkflowWaitWakeJobTest {

    private TenantSchemaIterator iterator;
    private WorkflowWaitResumeService resumeService;
    private ObjectProvider<PgAdvisoryJobLock> lockProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        iterator = mock(TenantSchemaIterator.class);
        resumeService = mock(WorkflowWaitResumeService.class);
        lockProvider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<String> action = invocation.getArgument(0);
            action.accept("tenant_a");
            action.accept("tenant_b");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
    }

    @Test
    void withTheFeatureOffNothingIsSwept() {
        new WorkflowWaitWakeJob(iterator, resumeService, lockProvider, false).wakeDueWaits();

        verify(iterator, never()).forEachActiveTenantSchema(any());
        verify(resumeService, never()).sweepDue(any());
    }

    @Test
    void withTheFeatureOnEveryActiveTenantIsSweptWhenThereIsNoLock() {
        new WorkflowWaitWakeJob(iterator, resumeService, lockProvider, true).wakeDueWaits();

        verify(resumeService, times(2)).sweepDue(any(Instant.class));
    }

    @Test
    void whenALockBeanExistsTheSweepRunsInsideIt() {
        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        org.mockito.Mockito.when(lockProvider.getIfAvailable()).thenReturn(lock);
        doAnswer(invocation -> {
            Runnable body = invocation.getArgument(1);
            body.run();
            return true;
        }).when(lock).tryRunExclusive(org.mockito.ArgumentMatchers.eq("workflow-wait-wake"), any(Runnable.class));

        new WorkflowWaitWakeJob(iterator, resumeService, lockProvider, true).wakeDueWaits();

        verify(lock).tryRunExclusive(org.mockito.ArgumentMatchers.eq("workflow-wait-wake"), any(Runnable.class));
        verify(resumeService, times(2)).sweepDue(any(Instant.class));
    }
}
