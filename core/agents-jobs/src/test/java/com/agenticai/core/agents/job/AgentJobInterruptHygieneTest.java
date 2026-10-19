package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Trap (3) from {@link AgentJobExecution}'s javadoc: a cancelled job leaves its worker thread
 * interrupted, and returning that thread to a POOL still flagged makes the NEXT, entirely unrelated
 * job die instantly with what looks exactly like a flaky upstream error. Cheap to get wrong,
 * expensive to diagnose.
 */
class AgentJobInterruptHygieneTest {

    private final ExecutorService oneThread = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        oneThread.shutdownNow();
        TenantContext.clear();
    }

    @Test
    void aCancelledJobLeavesNoInterruptFlagForTheNextTaskOnTheSameThread() throws Exception {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        // The shape a real cancel takes once it reaches the worker: the turn
        // aborts and the thread is left interrupted.
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        invocation -> {
                            Thread.currentThread().interrupt();
                            throw new CancellationException("worker interrupted");
                        });

        AgentJobExecution execution =
                new AgentJobExecution(
                        repository,
                        chatService,
                        mock(ToolCallLogRepository.class),
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class));

        oneThread
                .submit(() -> execution.run(job.getId(), AgentJobTestSupport.TENANT))
                .get(20, TimeUnit.SECONDS);

        assertEquals(AgentJobStatus.CANCELLED, job.getStatus());

        // Evaluated INSIDE the pooled thread on purpose - a method reference on
        // the test thread's own Thread object would answer the wrong question.
        boolean nextTaskSawAnInterrupt =
                oneThread
                        .submit(() -> Thread.currentThread().isInterrupted())
                        .get(10, TimeUnit.SECONDS);
        assertFalse(
                nextTaskSawAnInterrupt,
                "the worker thread went back to the pool still interrupted");
    }
}
