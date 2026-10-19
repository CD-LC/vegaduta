package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * The IN-BAND half of the wall clock: {@code block(remaining)} around the agent turn.
 *
 * <p>This half is cheap and catches the streaming case, but it is explicitly NOT the whole
 * mechanism - {@code block}'s timer cannot fire for a PLANNED/CAREFUL/RIGOROUS turn, because {@code
 * AgentChatService#planThenRespond} does blocking work inside a {@code Flux.defer} with no {@code
 * publishOn}, so the worker is already inside the LLM call before the timer is ever consulted. The
 * out-of-band backstop that covers that case is tested in {@link AgentJobDeadlineSweepTest};
 * neither test alone proves the timeout works.
 */
class AgentJobTimeoutTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aTurnThatNeverCompletesIsFailedWithATimeoutReasonRatherThanHangingTheWorker() {
        // One second, not the ten-minute default: this test asserts behaviour,
        // not patience.
        AgentJob job = AgentJobTestSupport.queuedJob(1, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.never());

        Instant before = Instant.now();
        new AgentJobExecution(
                        repository,
                        chatService,
                        mock(ToolCallLogRepository.class),
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class))
                .run(job.getId(), AgentJobTestSupport.TENANT);
        Duration took = Duration.between(before, Instant.now());

        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("TIMEOUT", job.getErrorCode());
        assertTrue(
                job.getErrorMessage().contains("1s"),
                "the timeout message should name the budget that was exceeded: "
                        + job.getErrorMessage());
        assertNotNull(job.getFinishedAt());
        assertTrue(
                took.compareTo(Duration.ofSeconds(20)) < 0,
                "the worker was not released promptly: " + took);
    }

    @Test
    void theDeadlineIsStampedAtClaimNotAtEnqueueSoAQueuedJobKeepsItsFullBudget() {
        // A job that waited in a backed-up queue must not be born half-expired.
        AgentJob job = AgentJobTestSupport.queuedJob(600, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("done"));

        // Enqueue time is already in the past relative to the claim below.
        assertNull(job.getStartedAt());

        Instant claimedAfter = Instant.now();
        new AgentJobExecution(
                        repository,
                        chatService,
                        mock(ToolCallLogRepository.class),
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class))
                .run(job.getId(), AgentJobTestSupport.TENANT);

        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertNotNull(job.getStartedAt());
        assertTrue(
                !job.getStartedAt().isBefore(claimedAfter),
                "started_at must be stamped at claim, not carried over from enqueue");
    }
}
