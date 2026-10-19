package com.agenticai.core.agents.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.ownership.JobLeaseStore;
import com.agenticai.core.agents.job.ownership.JobOwnership;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * The worker-side half of ownership: with {@code app.agent-jobs.ownership.enabled=true} a worker
 * must (a) leave a row alone when the interrupt it saw was this replica shutting down, and (b)
 * never write a terminal state over a row another replica now owns. With ownership off (or absent)
 * behaviour is unchanged, which the rest of this package's tests pin.
 */
class AgentJobOwnershipExecutionTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AgentJobProperties enabledProperties() {
        AgentJobProperties properties = AgentJobTestSupport.properties();
        properties.getOwnership().setEnabled(true);
        return properties;
    }

    private static AgentJobExecution execution(
            AgentJobRepository repository,
            AgentChatService chat,
            AgentJobProperties properties,
            JobOwnership ownership) {
        AgentJobExecution execution =
                new AgentJobExecution(
                        repository,
                        chat,
                        mock(ToolCallLogRepository.class),
                        properties,
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class));
        if (ownership != null) {
            execution.setOwnership(ownership);
        }
        return execution;
    }

    @Test
    void anInterruptCausedByShutdownLeavesTheRowRunningForAdoptionInsteadOfCancelling() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        invocation -> {
                            Thread.currentThread().interrupt();
                            throw new CancellationException("worker interrupted by shutdown");
                        });
        AgentJobProperties properties = enabledProperties();
        JobOwnership ownership = new JobOwnership(properties, mock(JobLeaseStore.class));
        ownership.beginShutdown();

        execution(repository, chat, properties, ownership)
                .run(job.getId(), AgentJobTestSupport.TENANT);

        assertThat(job.getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(job.getOwnerInstance()).isEqualTo(ownership.ownerId());
        verify(repository, never()).save(any(AgentJob.class));
        Thread.interrupted(); // the test thread ran the worker body inline; do not leak the flag
    }

    @Test
    void theClaimStampsTheReplicaIdWithItsPerProcessSuffixWhenOwnershipIsOn() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("done"));
        AgentJobProperties properties = enabledProperties();
        JobOwnership ownership = new JobOwnership(properties, mock(JobLeaseStore.class));

        execution(repository, chat, properties, ownership)
                .run(job.getId(), AgentJobTestSupport.TENANT);

        verify(repository)
                .claim(
                        org.mockito.ArgumentMatchers.eq(job.getId()),
                        org.mockito.ArgumentMatchers.eq(ownership.ownerId()),
                        any(Instant.class),
                        any(Instant.class));
        assertThat(ownership.ownerId()).isNotEqualTo(AgentJobExecutor.instanceId());
        assertThat(job.getStatus()).isEqualTo(AgentJobStatus.SUCCEEDED);
    }

    @Test
    void aZombieWorkerDoesNotOverwriteARowAnotherReplicaNowOwns() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        invocation -> {
                            // While this worker was paused, another replica adopted the job and
                            // claimed it.
                            job.requeue(Instant.now());
                            job.markRunning("other-replica:1:abcd1234", Instant.now());
                            return Flux.just("late result from the zombie");
                        });
        AgentJobProperties properties = enabledProperties();
        JobOwnership ownership = new JobOwnership(properties, mock(JobLeaseStore.class));

        execution(repository, chat, properties, ownership)
                .run(job.getId(), AgentJobTestSupport.TENANT);

        verify(repository, never()).save(any(AgentJob.class));
        assertThat(job.getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        assertThat(job.getOwnerInstance()).isEqualTo("other-replica:1:abcd1234");
        assertThat(job.getResultText()).isNull();
    }

    @Test
    void aTypedFailureDuringShutdownDoesNotWriteATerminalRowEither() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chat = mock(AgentChatService.class);
        // The shutdown interrupt can surface as a typed failure with no interrupt flag set.
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(
                        Flux.error(
                                new com.agenticai.core.agents.core.TenantUnavailableException(
                                        "interrupted while stopping")));
        AgentJobProperties properties = enabledProperties();
        JobOwnership ownership = new JobOwnership(properties, mock(JobLeaseStore.class));
        ownership.beginShutdown();

        execution(repository, chat, properties, ownership)
                .run(job.getId(), AgentJobTestSupport.TENANT);

        assertThat(job.getStatus()).isEqualTo(AgentJobStatus.RUNNING);
        verify(repository, never()).save(any(AgentJob.class));
    }

    @Test
    void withOwnershipOffTheClaimAndTerminalWriteAreExactlyToday() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("done"));
        AgentJobProperties properties =
                AgentJobTestSupport.properties(); // ownership.enabled defaults to false
        JobOwnership ownership = new JobOwnership(properties, mock(JobLeaseStore.class));

        execution(repository, chat, properties, ownership)
                .run(job.getId(), AgentJobTestSupport.TENANT);

        verify(repository)
                .claim(
                        org.mockito.ArgumentMatchers.eq(job.getId()),
                        org.mockito.ArgumentMatchers.eq(AgentJobExecutor.instanceId()),
                        any(Instant.class),
                        any(Instant.class));
        assertThat(job.getStatus()).isEqualTo(AgentJobStatus.SUCCEEDED);
    }
}
