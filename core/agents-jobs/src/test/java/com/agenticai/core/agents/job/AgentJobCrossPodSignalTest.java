package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The cross-pod half of cancel and deadline: the pod that RUNS a job is not necessarily the pod
 * that received the cancel or ran the deadline sweep, so the row is the only signal that always
 * reaches it.
 *
 * <p>Plain Mockito, no Spring context, per the module's standing rule. The heartbeat interval is
 * floored at one second by the executor, so the tests that wait for a tick wait a few seconds at
 * most.
 */
class AgentJobCrossPodSignalTest {

    private static final String TENANT = "t_cross_pod";

    private AgentJobExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        TenantContext.clear();
    }

    @Test
    void aRowTerminalisedByAnotherPodInterruptsTheLocalWorkerWhenEnabled() throws Exception {
        UUID jobId = UUID.randomUUID();
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.isCancelRequested(jobId)).thenReturn(Optional.of(false));
        // What a deadline sweep on ANOTHER pod leaves behind: FAILED, no cancel flag.
        when(repository.findStatusById(jobId)).thenReturn(Optional.of(AgentJobStatus.FAILED));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AgentJobExecution execution = blockingExecution(jobId, started, interrupted);

        AgentJobProperties properties = fastHeartbeat();
        properties.setInterruptOnExternalTerminal(true);
        executor = new AgentJobExecutor(repository, execution, properties);

        assertTrue(executor.tryDispatch(jobId, TENANT));
        assertTrue(started.await(5, TimeUnit.SECONDS), "the worker never started");

        assertTrue(
                interrupted.await(6, TimeUnit.SECONDS),
                "the heartbeat tick must interrupt a worker whose row another pod already failed");
    }

    @Test
    void theExternalTerminalCheckIsOffByDefaultSoTodaysSingleProcessBehaviourIsUnchanged()
            throws Exception {
        UUID jobId = UUID.randomUUID();
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.isCancelRequested(jobId)).thenReturn(Optional.of(false));
        when(repository.findStatusById(jobId)).thenReturn(Optional.of(AgentJobStatus.FAILED));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AgentJobExecution execution = blockingExecution(jobId, started, interrupted);

        executor = new AgentJobExecutor(repository, execution, fastHeartbeat());

        assertTrue(executor.tryDispatch(jobId, TENANT));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertFalse(
                interrupted.await(2500, TimeUnit.MILLISECONDS),
                "with the flag off the tick must not act on the status column");
        verify(repository, never()).findStatusById(any());
    }

    @Test
    void aRunningRowIsNotInterruptedEvenWhenTheExternalCheckIsOn() throws Exception {
        UUID jobId = UUID.randomUUID();
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.isCancelRequested(jobId)).thenReturn(Optional.of(false));
        when(repository.findStatusById(jobId)).thenReturn(Optional.of(AgentJobStatus.RUNNING));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AgentJobExecution execution = blockingExecution(jobId, started, interrupted);

        AgentJobProperties properties = fastHeartbeat();
        properties.setInterruptOnExternalTerminal(true);
        executor = new AgentJobExecutor(repository, execution, properties);

        assertTrue(executor.tryDispatch(jobId, TENANT));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertFalse(interrupted.await(2500, TimeUnit.MILLISECONDS));
    }

    @Test
    void aCancelFlagWrittenByAnotherPodStillInterruptsTheLocalWorker() throws Exception {
        UUID jobId = UUID.randomUUID();
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.isCancelRequested(jobId)).thenReturn(Optional.of(true));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AgentJobExecution execution = blockingExecution(jobId, started, interrupted);

        executor = new AgentJobExecutor(repository, execution, fastHeartbeat());

        assertTrue(executor.tryDispatch(jobId, TENANT));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(interrupted.await(6, TimeUnit.SECONDS));
    }

    @Test
    void enqueueModeWritesTheQueuedRowAndNeverDispatchesInThisJvm() {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.save(any(AgentJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AgentRepository agentRepository = mock(AgentRepository.class);
        Agent agent = mock(Agent.class);
        when(agentRepository.findById(any())).thenReturn(Optional.of(agent));
        AgentJobExecutor mockExecutor = mock(AgentJobExecutor.class);
        AgentJobProperties properties = new AgentJobProperties();
        properties.setRunNowMode(AgentJobProperties.RunNowMode.ENQUEUE);
        AgentJobService service =
                new AgentJobService(
                        repository,
                        agentRepository,
                        mock(AgentService.class),
                        mockExecutor,
                        properties,
                        new SimpleMeterRegistry(),
                        new ObjectMapper(),
                        15);
        TenantContext.set(TenantContext.of(TENANT, "alice@example.com", Set.of("ROLE_user")));

        service.submit(
                UUID.randomUUID(),
                new AgentJobStartRequest("Summarise the tickets", null, null, null));

        verify(repository).save(any(AgentJob.class));
        verify(mockExecutor, never()).tryDispatch(any(), anyString());
    }

    @Test
    void inlineModeIsTheDefaultAndStillDispatchesLocally() {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.save(any(AgentJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AgentRepository agentRepository = mock(AgentRepository.class);
        Agent agent = mock(Agent.class);
        when(agentRepository.findById(any())).thenReturn(Optional.of(agent));
        AgentJobExecutor mockExecutor = mock(AgentJobExecutor.class);
        when(mockExecutor.tryDispatch(any(), anyString())).thenReturn(true);
        AgentJobProperties properties = new AgentJobProperties();
        assertEquals(AgentJobProperties.RunNowMode.INLINE, properties.getRunNowMode());
        AgentJobService service =
                new AgentJobService(
                        repository,
                        agentRepository,
                        mock(AgentService.class),
                        mockExecutor,
                        properties,
                        new SimpleMeterRegistry(),
                        new ObjectMapper(),
                        15);
        TenantContext.set(TenantContext.of(TENANT, "alice@example.com", Set.of("ROLE_user")));

        service.submit(
                UUID.randomUUID(),
                new AgentJobStartRequest("Summarise the tickets", null, null, null));

        verify(mockExecutor).tryDispatch(any(), anyString());
    }

    private static AgentJobProperties fastHeartbeat() {
        AgentJobProperties properties = new AgentJobProperties();
        properties.setHeartbeatInterval(Duration.ofSeconds(1));
        return properties;
    }

    /** An execution whose worker parks until interrupted, and reports that it was. */
    private static AgentJobExecution blockingExecution(
            UUID jobId, CountDownLatch started, CountDownLatch interrupted) {
        AgentJobExecution execution = mock(AgentJobExecution.class);
        doAnswer(
                        invocation -> {
                            started.countDown();
                            try {
                                Thread.sleep(30_000);
                            } catch (InterruptedException e) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                            return null;
                        })
                .when(execution)
                .run(jobId, TENANT);
        return execution;
    }
}
