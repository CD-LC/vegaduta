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
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

class AgentJobShutdownReleaseTest {

    private InMemoryJobLeaseStore store;
    private AgentJobProperties properties;
    private JobOwnership ownership;
    private AgentJobExecutor executor;
    private AgentJobShutdownRelease release;
    private ApplicationContext context;
    private ContextClosedEvent closed;

    @BeforeEach
    void setUp() {
        store = new InMemoryJobLeaseStore();
        properties = new AgentJobProperties();
        properties.getOwnership().setEnabled(true);
        ownership = new JobOwnership(properties, store);
        executor = mock(AgentJobExecutor.class);
        PgAdvisoryJobLock jobLock = mock(PgAdvisoryJobLock.class);
        when(jobLock.tryRunExclusive(anyString(), any(Runnable.class))).thenReturn(true);
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept("tenant_acme");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());
        JobResumeFactsReader facts = mock(JobResumeFactsReader.class);
        when(facts.read(any())).thenReturn(new Facts(false, TurnState.NONE, false, false, 0L));
        AgentJobLeaseSweep sweep = new AgentJobLeaseSweep(
            store, facts, properties, ownership, executor, iterator, jobLock, new SimpleMeterRegistry());
        context = mock(ApplicationContext.class);
        closed = new ContextClosedEvent(context);
        release = new AgentJobShutdownRelease(ownership, executor, store, sweep, new SimpleMeterRegistry(), context);
    }

    private InMemoryJobLeaseStore.Row runningHere() {
        InMemoryJobLeaseStore.Row row = store.enqueue(Instant.now());
        store.claim(row.id, ownership.ownerId(), Instant.now());
        return row;
    }

    @Test
    void releasesWhatItOwnsAndHandsSafeJobsBackToTheQueueImmediately() {
        InMemoryJobLeaseStore.Row row = runningHere();
        when(executor.quiesceForShutdown()).thenReturn(List.of(new AgentJobExecutor.OwnedJob(row.id, row.tenantId)));

        release.onContextClosed(closed);

        assertThat(ownership.isShuttingDown()).isTrue();
        // released (lease declared over) and, being zero-work, re-queued at once for any replica to claim
        assertThat(row.status).isEqualTo(AgentJobStatus.QUEUED);
        assertThat(row.owner).isNull();
        assertThat(row.resumeReason).contains("zero-work");
        // never dispatched into this dying process
        verify(executor, never()).tryDispatch(any(), anyString());
    }

    @Test
    void aJobThatCannotBeProvenSafeIsFailedLoudlyAtShutdownNotResumed() {
        InMemoryJobLeaseStore.Row row = runningHere();
        store.incrementTurnsUsed(row.id); // a turn started: resume flags are off => not safe
        when(executor.quiesceForShutdown()).thenReturn(List.of(new AgentJobExecutor.OwnedJob(row.id, row.tenantId)));

        release.onContextClosed(closed);

        assertThat(row.status).isEqualTo(AgentJobStatus.FAILED);
        assertThat(row.errorCode).isEqualTo(JobResumePlanner.ERR_ORPHANED);
    }

    @Test
    void doesNotReleaseARowAnotherReplicaOwns() {
        InMemoryJobLeaseStore.Row row = store.enqueue(Instant.now());
        store.claim(row.id, "other-replica:1:cccc", Instant.now());
        when(executor.quiesceForShutdown()).thenReturn(List.of(new AgentJobExecutor.OwnedJob(row.id, row.tenantId)));

        release.onContextClosed(closed);

        assertThat(row.status).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(row.owner).isEqualTo("other-replica:1:cccc");
    }

    @Test
    void isIdempotentAndANoOpWhenOwnershipIsOff() {
        properties.getOwnership().setEnabled(false);

        release.onContextClosed(closed);

        verify(executor, never()).quiesceForShutdown();
        assertThat(ownership.isShuttingDown()).isFalse();

        properties.getOwnership().setEnabled(true);
        when(executor.quiesceForShutdown()).thenReturn(List.of());
        release.onContextClosed(closed);
        release.onContextClosed(closed);
        verify(executor).quiesceForShutdown();
    }

    @Test
    void theCloseOfAnotherContextDoesNotStartTheShutdownRelease() {
        ContextClosedEvent childClosed = new ContextClosedEvent(mock(ApplicationContext.class));

        release.onContextClosed(childClosed);

        verify(executor, never()).quiesceForShutdown();
        assertThat(ownership.isShuttingDown()).isFalse();
    }

    @Test
    void aHeartbeatTickThatWroteAfterTheFirstReleaseIsOverriddenByTheSecondRelease() {
        InMemoryJobLeaseStore.Row row = runningHere();
        when(executor.quiesceForShutdown()).thenReturn(List.of(new AgentJobExecutor.OwnedJob(row.id, row.tenantId)));
        // The scheduler had not terminated: a straggler tick renews the lease after the first release.
        when(executor.heartbeatQuiesced()).thenAnswer(invocation -> {
            row.heartbeat = Instant.now();
            return false;
        });

        release.onContextClosed(closed);

        // Without the second release the renewed lease would keep the row RUNNING until it expired.
        assertThat(row.status).isEqualTo(AgentJobStatus.QUEUED);
        assertThat(row.resumeReason).contains("zero-work");
    }

    @Test
    void anExecutorFailureNeverPropagatesIntoShutdown() {
        when(executor.quiesceForShutdown()).thenThrow(new IllegalStateException("boom"));

        release.onContextClosed(closed);

        assertThat(ownership.isShuttingDown()).isTrue();
    }

    @Test
    void theEventTypeIsTheContextClosedEvent() throws Exception {
        var method = AgentJobShutdownRelease.class.getMethod("onContextClosed", ContextClosedEvent.class);
        var listener = method.getAnnotation(org.springframework.context.event.EventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.value()).containsExactly(ContextClosedEvent.class);
    }
}
