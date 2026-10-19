package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.ownership.JobLeaseStore;
import com.agenticai.core.agents.job.ownership.JobOwnership;
import com.agenticai.core.agents.job.ownership.JobReplicaId;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The executor half of ownership: lease renewal and self-fencing on the heartbeat tick, and the shutdown
 * quiesce. Plain Mockito, like {@link AgentJobCrossPodSignalTest}. The assertions do not depend on the
 * scheduler's timing: each test drives {@link AgentJobExecutor#heartbeatTick()} explicitly (the scheduled tick
 * running alongside it is idempotent), and the replica ids are resolved once up front so the first
 * {@code InetAddress.getLocalHost()} lookup cannot stall a tick on a CI box without DNS.
 */
class AgentJobOwnershipExecutorTest {

    private static final String TENANT = "t_ownership";

    private AgentJobExecutor executor;

    @BeforeAll
    static void warmReplicaIds() {
        JobReplicaId.current();
        AgentJobExecutor.instanceId();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        TenantContext.clear();
    }

    private static AgentJobProperties ownershipProperties(boolean enabled) {
        AgentJobProperties properties = new AgentJobProperties();
        properties.setHeartbeatInterval(Duration.ofSeconds(1));
        properties.getOwnership().setEnabled(enabled);
        properties.getOwnership().setHeartbeatSeconds(1);
        return properties;
    }

    private static AgentJobExecution blockingExecution(UUID jobId, CountDownLatch started, CountDownLatch interrupted) {
        AgentJobExecution execution = mock(AgentJobExecution.class);
        doAnswer(invocation -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(execution).run(jobId, TENANT);
        return execution;
    }

    @Test
    void aWorkerWhoseLeaseWasLostToAnotherReplicaIsInterrupted() throws Exception {
        UUID jobId = UUID.randomUUID();
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.isCancelRequested(jobId)).thenReturn(Optional.of(false));
        JobLeaseStore store = mock(JobLeaseStore.class);
        when(store.renew(any(), any(), any())).thenReturn(false);
        when(store.view(jobId)).thenReturn(Optional.of(
            new JobLeaseStore.LeaseView(AgentJobStatus.RUNNING, "adopter-replica:9:ffff0000")));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        AgentJobProperties properties = ownershipProperties(true);
        executor = new AgentJobExecutor(repository, blockingExecution(jobId, started, interrupted), properties);
        executor.setOwnership(new JobOwnership(properties, store));

        assertTrue(executor.tryDispatch(jobId, TENANT));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.heartbeatTick();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS),
            "a zombie worker must be stopped once another replica owns its job");
        verify(repository, never()).touchHeartbeat(any(), any());
    }

    @Test
    void aWorkerThatKeepsItsLeaseIsNotInterrupted() throws Exception {
        UUID jobId = UUID.randomUUID();
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.isCancelRequested(jobId)).thenReturn(Optional.of(false));
        JobLeaseStore store = mock(JobLeaseStore.class);
        when(store.renew(any(), any(), any())).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        AgentJobProperties properties = ownershipProperties(true);
        executor = new AgentJobExecutor(repository, blockingExecution(jobId, started, interrupted), properties);
        executor.setOwnership(new JobOwnership(properties, store));

        assertTrue(executor.tryDispatch(jobId, TENANT));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.heartbeatTick();
        verify(store, org.mockito.Mockito.atLeastOnce()).renew(any(), any(), any());
        // stopLocally interrupts synchronously inside the tick, so a short wait is ample to observe it
        assertFalse(interrupted.await(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void aMissedRenewalForAJobNotClaimedYetNeverInterruptsIt() throws Exception {
        UUID jobId = UUID.randomUUID();
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.isCancelRequested(jobId)).thenReturn(Optional.of(false));
        JobLeaseStore store = mock(JobLeaseStore.class);
        when(store.renew(any(), any(), any())).thenReturn(false);
        when(store.view(jobId)).thenReturn(Optional.of(new JobLeaseStore.LeaseView(AgentJobStatus.QUEUED, null)));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        AgentJobProperties properties = ownershipProperties(true);
        executor = new AgentJobExecutor(repository, blockingExecution(jobId, started, interrupted), properties);
        executor.setOwnership(new JobOwnership(properties, store));

        assertTrue(executor.tryDispatch(jobId, TENANT));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.heartbeatTick();
        verify(store, org.mockito.Mockito.atLeastOnce()).view(jobId);
        assertFalse(interrupted.await(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void withOwnershipDisabledTheHeartbeatIsTheUnconditionalLegacyTouch() throws Exception {
        UUID jobId = UUID.randomUUID();
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.isCancelRequested(jobId)).thenReturn(Optional.of(false));
        JobLeaseStore store = mock(JobLeaseStore.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        AgentJobProperties properties = ownershipProperties(false);
        executor = new AgentJobExecutor(repository, blockingExecution(jobId, started, interrupted), properties);
        executor.setOwnership(new JobOwnership(properties, store));

        assertTrue(executor.tryDispatch(jobId, TENANT));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.heartbeatTick();
        verify(repository, org.mockito.Mockito.atLeastOnce()).touchHeartbeat(any(), any());
        assertFalse(interrupted.await(300, TimeUnit.MILLISECONDS));
        verify(store, never()).renew(any(), any(), any());
    }

    @Test
    void quiesceInterruptsEveryWorkerReportsThemAndStopsNewDispatches() throws Exception {
        UUID jobId = UUID.randomUUID();
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.isCancelRequested(jobId)).thenReturn(Optional.of(false));
        JobLeaseStore store = mock(JobLeaseStore.class);
        when(store.renew(any(), any(), any())).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        AgentJobProperties properties = ownershipProperties(true);
        executor = new AgentJobExecutor(repository, blockingExecution(jobId, started, interrupted), properties);
        JobOwnership ownership = new JobOwnership(properties, store);
        executor.setOwnership(ownership);

        assertTrue(executor.tryDispatch(jobId, TENANT));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        ownership.beginShutdown();
        List<AgentJobExecutor.OwnedJob> owned = executor.quiesceForShutdown();

        assertEquals(List.of(new AgentJobExecutor.OwnedJob(jobId, TENANT)), owned);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the worker must be interrupted before its lease is released");
        assertFalse(executor.tryDispatch(UUID.randomUUID(), TENANT), "nothing may start on a replica that is shutting down");
        assertTrue(executor.heartbeatQuiesced(), "the heartbeat scheduler must be terminated after a quiesce");
        assertEquals(0, executor.activeCount(), "quiesce waits (bounded) for the interrupted workers to unwind");
    }
}
