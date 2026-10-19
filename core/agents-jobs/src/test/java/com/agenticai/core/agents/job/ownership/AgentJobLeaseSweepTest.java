package com.agenticai.core.agents.job.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.Facts;
import com.agenticai.core.agents.job.ownership.JobResumePlanner.TurnState;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentJobLeaseSweepTest {

    private InMemoryJobLeaseStore store;
    private AgentJobProperties properties;
    private JobOwnership ownership;
    private AgentJobExecutor executor;
    private PgAdvisoryJobLock jobLock;
    private TenantSchemaIterator iterator;
    private JobResumeFactsReader facts;
    private AgentJobLeaseSweep sweep;

    @BeforeEach
    void setUp() {
        store = new InMemoryJobLeaseStore();
        properties = new AgentJobProperties();
        properties.getOwnership().setEnabled(true);
        ownership = new JobOwnership(properties, store);
        executor = mock(AgentJobExecutor.class);
        jobLock = mock(PgAdvisoryJobLock.class);
        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        });
        iterator = mock(TenantSchemaIterator.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept("tenant_acme");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
        facts = mock(JobResumeFactsReader.class);
        when(facts.read(any())).thenReturn(new Facts(false, TurnState.NONE, false, false, 0L));
        sweep = new AgentJobLeaseSweep(store, facts, properties, ownership, executor, iterator, jobLock,
            new SimpleMeterRegistry());
    }

    private InMemoryJobLeaseStore.Row abandonedJob() {
        InMemoryJobLeaseStore.Row row = store.enqueue(Instant.now().minusSeconds(900));
        store.claim(row.id, "dead-replica:1:aaaa", Instant.now().minusSeconds(600));
        return row;
    }

    @Test
    void doesNothingAndTouchesNoLockWhenOwnershipIsOff() {
        properties.getOwnership().setEnabled(false);
        InMemoryJobLeaseStore.Row row = abandonedJob();

        sweep.sweep();

        verify(jobLock, never()).tryRunExclusive(anyString(), any(Runnable.class));
        assertThat(row.status).isEqualTo(AgentJobStatus.RUNNING);
    }

    @Test
    void requeuesAnExpiredZeroWorkJobAndHandsItToTheLocalDispatcher() {
        InMemoryJobLeaseStore.Row row = abandonedJob();

        sweep.sweep();

        assertThat(row.status).isEqualTo(AgentJobStatus.QUEUED);
        verify(executor).tryDispatch(row.id, row.tenantId);
    }

    @Test
    void leavesAJobAliveOnThisReplicaAlone() {
        InMemoryJobLeaseStore.Row row = abandonedJob();
        when(executor.isActiveHere(row.id)).thenReturn(true);

        sweep.sweep();

        assertThat(row.status).isEqualTo(AgentJobStatus.RUNNING);
        verify(executor, never()).tryDispatch(any(), anyString());
    }

    @Test
    void leavesAFreshLeaseAlone() {
        InMemoryJobLeaseStore.Row row = store.enqueue(Instant.now());
        store.claim(row.id, "live-replica:1:bbbb", Instant.now());

        sweep.sweep();

        assertThat(row.status).isEqualTo(AgentJobStatus.RUNNING);
    }

    @Test
    void doesNothingOnceThisProcessIsShuttingDown() {
        InMemoryJobLeaseStore.Row row = abandonedJob();
        ownership.beginShutdown();

        sweep.sweep();

        verify(jobLock, never()).tryRunExclusive(anyString(), any(Runnable.class));
        assertThat(row.status).isEqualTo(AgentJobStatus.RUNNING);
    }

    @Test
    void aFailingFactsLookupFailsTheJobSafelyAndDoesNotStopTheTick() {
        InMemoryJobLeaseStore.Row first = abandonedJob();
        InMemoryJobLeaseStore.Row second = abandonedJob();
        when(facts.read(any())).thenThrow(new IllegalStateException("tool log down"));

        sweep.sweep();

        assertThat(first.status).isEqualTo(AgentJobStatus.FAILED);
        assertThat(second.status).isEqualTo(AgentJobStatus.FAILED);
        assertThat(first.errorCode).isEqualTo(JobResumePlanner.ERR_ORPHANED);
    }

    @Test
    void respectsTheConfiguredAttemptBound() {
        properties.getOwnership().setResumeMaxAttempts(1);
        InMemoryJobLeaseStore.Row row = abandonedJob(); // claimed once => attempt 1 >= bound 1

        sweep.sweep();

        assertThat(row.status).isEqualTo(AgentJobStatus.FAILED);
        assertThat(row.errorCode).isEqualTo(JobResumePlanner.ERR_ATTEMPTS_EXHAUSTED);
        assertThat(row.errorMessage).contains("resume-max-attempts");
    }
}
