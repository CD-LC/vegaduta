package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.dto.AgentJobResponse;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Cancellation, from both ends: the worker that has to notice, and the service an operator actually
 * clicks.
 *
 * <p>The ordering under test is "durable effect first, best-effort interrupt second". The flag is
 * authoritative even when the interrupt does nothing - there is no cross-replica interrupt without
 * a message bus and this workstream adds none, so a design that relied on the interrupt would
 * quietly stop working the day a second replica exists.
 */
class AgentJobCancellationTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void interruptingARunningJobCancelsItsSubscriptionAndLandsTheRowCancelled() throws Exception {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);

        // The realistic sequence: the operator's cancel writes the durable flag,
        // and only then is the local worker interrupted.
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        when(repository.isCancelRequested(job.getId()))
                .thenAnswer(invocation -> Optional.of(cancelFlag.get()));

        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch subscriptionCancelled = new CountDownLatch(1);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(
                        Flux.<String>never()
                                .doOnSubscribe(s -> subscribed.countDown())
                                .doOnCancel(subscriptionCancelled::countDown));

        AgentJobExecution execution =
                new AgentJobExecution(
                        repository,
                        chatService,
                        mock(ToolCallLogRepository.class),
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class));

        FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            execution.run(job.getId(), AgentJobTestSupport.TENANT);
                            return null;
                        });
        Thread worker = new Thread(task, "agent-job-test-worker");
        worker.start();

        assertTrue(subscribed.await(20, TimeUnit.SECONDS), "the turn never started");
        cancelFlag.set(true);
        task.cancel(true);

        assertTrue(
                subscriptionCancelled.await(20, TimeUnit.SECONDS),
                "cancelling the worker did not cancel the underlying turn - the LLM call would keep"
                        + " running and keep billing");
        worker.join(TimeUnit.SECONDS.toMillis(20));

        assertEquals(AgentJobStatus.CANCELLED, job.getStatus());
        assertNotNull(job.getFinishedAt());
    }

    @Test
    void aCancelObservedBeforeTheTurnStartsSkipsTheLlmCallEntirely() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        when(repository.isCancelRequested(job.getId())).thenReturn(Optional.of(true));
        AgentChatService chatService = mock(AgentChatService.class);

        new AgentJobExecution(
                        repository,
                        chatService,
                        mock(ToolCallLogRepository.class),
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class))
                .run(job.getId(), AgentJobTestSupport.TENANT);

        assertEquals(AgentJobStatus.CANCELLED, job.getStatus());
        verify(chatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void cancellingAQueuedJobKillsItOutrightWithNoWorkerToInterrupt() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        Fixture fixture = new Fixture(job);
        when(fixture.repository.cancelIfStillQueued(
                        eq(job.getId()), anyString(), any(Instant.class)))
                .thenReturn(1);

        fixture.service.cancel(job.getAgentId(), job.getId());

        verify(fixture.repository)
                .cancelIfStillQueued(eq(job.getId()), anyString(), any(Instant.class));
        // The guarded UPDATE won, so there is nothing running to signal.
        verify(fixture.repository, never()).requestCancel(any(), anyString(), any(Instant.class));
    }

    @Test
    void cancellingAClaimedJobWritesTheDurableFlagBeforeTheBestEffortInterrupt() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        job.markRunning("host-a:1", Instant.now());
        Fixture fixture = new Fixture(job);
        // Guarded on QUEUED, so a job a worker claimed a microsecond ago falls
        // through to the cooperative path instead of being stolen.
        when(fixture.repository.cancelIfStillQueued(
                        eq(job.getId()), anyString(), any(Instant.class)))
                .thenReturn(0);

        fixture.service.cancel(job.getAgentId(), job.getId());

        var order = org.mockito.Mockito.inOrder(fixture.repository, fixture.executor);
        order.verify(fixture.repository)
                .requestCancel(eq(job.getId()), anyString(), any(Instant.class));
        order.verify(fixture.executor).stopLocally(job.getId());
    }

    @Test
    void cancellingATerminalJobIsAnIdempotentNoOpAndReturnsTheRow() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        job.markRunning("host-a:1", Instant.now());
        job.succeed("already done", null, null, 0, Instant.now());
        Fixture fixture = new Fixture(job);

        AgentJobResponse response = fixture.service.cancel(job.getAgentId(), job.getId());

        // Never a 409: an operator hitting cancel twice, or hitting it exactly as
        // the job finishes, is not making a mistake - and the deadline sweep
        // takes the same path, so an error here would turn a normal race into a
        // logged failure.
        assertEquals(AgentJobStatus.SUCCEEDED, response.status());
        assertTrue(response.terminal());
        assertEquals("already done", response.resultText());
        verify(fixture.repository, never())
                .cancelIfStillQueued(any(), anyString(), any(Instant.class));
        verify(fixture.repository, never()).requestCancel(any(), anyString(), any(Instant.class));
        verify(fixture.executor, never()).stopLocally(any());
    }

    @Test
    void theRequesterCancelsTheirOwnJobWithRunnerAccessAlone() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        Fixture fixture = new Fixture(job);
        TenantContext.set(
                TenantContext.of(
                        AgentJobTestSupport.TENANT, AgentJobTestSupport.REQUESTED_BY, Set.of()));
        doThrow(new ResourceAccessDeniedException())
                .when(fixture.agentService)
                .requireAccess(job.getAgentId(), PermissionLevel.EDITOR);
        when(fixture.repository.cancelIfStillQueued(
                        eq(job.getId()), anyString(), any(Instant.class)))
                .thenReturn(1);

        fixture.service.cancel(job.getAgentId(), job.getId());

        verify(fixture.repository)
                .cancelIfStillQueued(eq(job.getId()), anyString(), any(Instant.class));
        verify(fixture.agentService, never())
                .requireAccess(job.getAgentId(), PermissionLevel.EDITOR);
    }

    @Test
    void anotherRunnerCannotCancelSomeoneElsesJob() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        Fixture fixture = new Fixture(job);
        TenantContext.set(TenantContext.of(AgentJobTestSupport.TENANT, "bob", Set.of()));
        doThrow(new ResourceAccessDeniedException())
                .when(fixture.agentService)
                .requireAccess(job.getAgentId(), PermissionLevel.EDITOR);

        assertThrows(
                ResourceAccessDeniedException.class,
                () -> fixture.service.cancel(job.getAgentId(), job.getId()));
        verify(fixture.repository, never())
                .cancelIfStillQueued(any(), anyString(), any(Instant.class));
        verify(fixture.repository, never()).requestCancel(any(), anyString(), any(Instant.class));
    }

    @Test
    void anAgentEditorCanCancelSomeoneElsesJob() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        Fixture fixture = new Fixture(job);
        TenantContext.set(TenantContext.of(AgentJobTestSupport.TENANT, "owner", Set.of()));
        when(fixture.repository.cancelIfStillQueued(
                        eq(job.getId()), anyString(), any(Instant.class)))
                .thenReturn(1);

        fixture.service.cancel(job.getAgentId(), job.getId());

        verify(fixture.agentService).requireAccess(job.getAgentId(), PermissionLevel.EDITOR);
        verify(fixture.repository)
                .cancelIfStillQueued(eq(job.getId()), anyString(), any(Instant.class));
    }

    /** An {@link AgentJobService} wired over one job, with access checks stubbed open. */
    private static final class Fixture {

        private final AgentJobRepository repository = mock(AgentJobRepository.class);
        private final AgentJobExecutor executor = mock(AgentJobExecutor.class);
        private final AgentService agentService = mock(AgentService.class);
        private final AgentJobService service;

        private Fixture(AgentJob job) {
            when(repository.findByIdAndAgentId(job.getId(), job.getAgentId()))
                    .thenReturn(Optional.of(job));
            service =
                    new AgentJobService(
                            repository,
                            mock(AgentRepository.class),
                            agentService,
                            executor,
                            AgentJobTestSupport.properties(),
                            new SimpleMeterRegistry(),
                            new ObjectMapper(),
                            15);
        }
    }
}
