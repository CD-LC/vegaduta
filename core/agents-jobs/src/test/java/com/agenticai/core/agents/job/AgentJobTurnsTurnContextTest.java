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

    @BeforeEach
    void setUp() {
        job = AgentJobTestSupport.queuedJob(60, 15);
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("done"));
        turns =
                new AgentJobTurns(
                        AgentJobTestSupport.repositoryFor(job),
                        chat,
                        mock(ToolCallLogRepository.class),
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
}
