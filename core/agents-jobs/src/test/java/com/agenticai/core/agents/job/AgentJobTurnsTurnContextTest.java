package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the agent-job entry: both turn shapes ({@code runOneTurn}: the goal turn with
 * the progress tool, and the tool-less repair turn) build the context from the tenant the job's
 * execution thread runs under.
 */
class AgentJobTurnsTurnContextTest {

    private final TenantContext tenant =
            TenantContext.of(AgentJobTestSupport.TENANT, "job-runner", Set.of("USER"));
    private AgentChatService chat;
    private AgentJobTurns turns;
    private AgentJob job;
    private ToolCallLogRepository toolCallLogs;

    @BeforeEach
    void setUp() {
        job = AgentJobTestSupport.queuedJob(60, 15);
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("done"));
        toolCallLogs = mock(ToolCallLogRepository.class);
        turns =
                new AgentJobTurns(
                        AgentJobTestSupport.repositoryFor(job),
                        chat,
                        toolCallLogs,
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class),
                        mock(AgentJobOutputContract.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Instant deadline() {
        return Instant.now().plus(Duration.ofSeconds(30));
    }

    private TurnContext capturedContext(ArgumentCaptor<ChatTurnRequest> request) {
        ArgumentCaptor<TurnContext> ctx = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(ctx.capture(), request.capture());
        return ctx.getValue();
    }

    @Test
    void theGoalTurnCarriesTheJobThreadsTenant() throws Exception {
        TenantContext.set(tenant);

        assertEquals("done", turns.runOneTurn(job, deadline(), "goal", false));

        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        TurnContext context = capturedContext(request);
        assertSame(tenant, context.tenant());
        assertEquals(job.getAgentId(), context.requestedAgentId());
        assertEquals(job.getSessionId(), context.sessionId());
        assertFalse(request.getValue().suppressAgentTools());
        assertEquals(1, request.getValue().extraTools().size(), "the progress tool is offered");
    }

    @Test
    void theToolLessRepairTurnCarriesTheJobThreadsTenant() throws Exception {
        TenantContext.set(tenant);

        assertEquals("done", turns.runOneTurn(job, deadline(), "repair", true));

        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        TurnContext context = capturedContext(request);
        assertSame(tenant, context.tenant());
        assertEquals(job.getAgentId(), context.requestedAgentId());
        assertTrue(request.getValue().suppressAgentTools());
    }

    @Test
    void withoutAThreadTenantBothTurnsBuildATenantlessContext() throws Exception {
        turns.runOneTurn(job, deadline(), "repair", true);

        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        assertNull(capturedContext(request).tenant());
    }

    @Test
    void bothTurnShapesCarryTheJobsDeadlineSoABufferedDepthIsNotCutAtItsOwnShorterTimeout() throws Exception {
        Instant deadline = deadline();

        turns.runOneTurn(job, deadline, "goal", false);
        ArgumentCaptor<ChatTurnRequest> goalRequest = ArgumentCaptor.forClass(ChatTurnRequest.class);
        TurnContext goal = capturedContext(goalRequest);

        assertEquals(deadline, goal.deadline(), "the job's own timeoutSeconds is the authority on how long the turn may take");
    }

    @Test
    void theToolLessRepairTurnCarriesTheJobsDeadlineToo() throws Exception {
        Instant deadline = deadline();

        turns.runOneTurn(job, deadline, "repair", true);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);

        assertEquals(deadline, capturedContext(request).deadline());
    }

    // --- the job's tool-call budget, enforced inside the turn --------------------------------------

    @Test
    void theGoalTurnCarriesWhatIsLeftOfTheJobsToolBudgetAsACapOnItsOwnCalls() throws Exception {
        // queuedJob(60, 15): the job may make 15 calls in all; 4 are already on its session.
        when(toolCallLogs.countByAgentIdAndSessionId(job.getAgentId(), job.getSessionId())).thenReturn(4L);

        turns.runOneTurn(job, deadline(), "goal", false);

        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        capturedContext(request);
        assertEquals(11, request.getValue().caps().getMaxToolSteps(),
                "the turn may make the calls the job has left, not just the per-turn ceiling");
    }

    @Test
    void aJobsCapNeverAsksForMoreThanItHasLeftEvenWhenItIsTheOnlyLimit() throws Exception {
        when(toolCallLogs.countByAgentIdAndSessionId(job.getAgentId(), job.getSessionId())).thenReturn(14L);

        turns.runOneTurn(job, deadline(), "goal", false);

        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        capturedContext(request);
        assertEquals(1, request.getValue().caps().getMaxToolSteps());
    }

    @Test
    void theToolLessRepairTurnCarriesNoToolCap() throws Exception {
        turns.runOneTurn(job, deadline(), "repair", true);

        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        capturedContext(request);
        assertNull(request.getValue().caps(), "a tool-less turn has no calls to cap");
    }

    @Test
    void oneJobsBoundaryCountNeverLeaksIntoAnotherJobsTurnOnTheSharedInstance() throws Exception {
        // AgentJobTurns is one instance shared by every worker thread: job B's boundary check must not
        // change what job A's turn is allowed to spend.
        AgentJob other = AgentJobTestSupport.queuedJob(60, 15);
        when(toolCallLogs.countByAgentIdAndSessionId(job.getAgentId(), job.getSessionId())).thenReturn(2L);
        when(toolCallLogs.countByAgentIdAndSessionId(other.getAgentId(), other.getSessionId())).thenReturn(9L);

        turns.guardBeforeTurn(job);
        turns.guardBeforeTurn(other); // interleaved: B's boundary check lands between A's check and A's turn
        turns.runOneTurn(job, deadline(), "goal", false);

        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        capturedContext(request);
        assertEquals(13, request.getValue().caps().getMaxToolSteps(), "A has 15 - 2 left, whatever B has used");
    }
}
