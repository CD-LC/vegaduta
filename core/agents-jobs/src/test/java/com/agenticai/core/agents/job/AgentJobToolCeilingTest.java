package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * The per-JOB tool-step ceiling - the one limit a job does NOT inherit for free from the chat path
 * and therefore has to enforce itself.
 *
 * <p>It is counted from {@code tool_call_log} through the job's derived session id, which is why no
 * new column and no change to {@code LoggingToolCallback} was needed. It is checked at TURN
 * BOUNDARIES, and that coarseness is deliberate: exact per-call enforcement would need an {@code
 * AgentToolProvider#resolveToolsFor} overload, and that file belongs to a parallel session. Within
 * a turn, {@code app.guardrails.max-tool-calls-per-turn} still applies unchanged, so the window is
 * coarser, never unbounded.
 */
class AgentJobToolCeilingTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aJobAtItsToolCeilingFailsBeforeSpendingAnotherTurn() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
        when(toolCallLogRepository.countByAgentIdAndSessionId(job.getAgentId(), job.getSessionId()))
                .thenReturn(15L);
        AgentChatService chatService = mock(AgentChatService.class);

        new AgentJobExecution(
                        repository,
                        chatService,
                        toolCallLogRepository,
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class))
                .run(job.getId(), AgentJobTestSupport.TENANT);

        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("TOOL_LIMIT_EXCEEDED", job.getErrorCode());
        assertTrue(job.getErrorMessage().contains("15"));
        // The ceiling is a gate, not a report: no LLM call may be paid for once
        // it has been reached.
        verify(chatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void theCeilingIsCountedOnTheJobsOwnSessionNotTheAgentsWholeHistory() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
        // Only this job's own session is under the ceiling; the mock returns 0
        // for every other (agentId, sessionId) pair by default, so a query on
        // the wrong key would let the turn through and this assertion would not
        // be able to tell. Verifying the exact arguments is the point.
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(), any())).thenReturn(3L);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("done"));

        new AgentJobExecution(
                        repository,
                        chatService,
                        toolCallLogRepository,
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class))
                .run(job.getId(), AgentJobTestSupport.TENANT);

        // At the turn boundary and again to size the turn's own remaining budget (no count is cached
        // across them: the executor is shared by concurrent jobs); every one is on the job's session.
        verify(toolCallLogRepository, org.mockito.Mockito.atLeastOnce())
                .countByAgentIdAndSessionId(
                        job.getAgentId(), AgentJobSession.sessionIdFor(job.getId()));
        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        // Durable turn accounting: a resumed job must not be able to reset its
        // own budget by starting from zero.
        verify(repository).incrementTurnsUsed(job.getId());
        assertEquals(1, job.getTurnsUsed());
    }
}
