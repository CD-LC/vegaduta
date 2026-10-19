package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * The budget-escape guard.
 *
 * <p>{@code PerUserBudgetService} keys its spend window on (tenant, subject, modality). If a job
 * ran under a synthetic per-job subject, every submission would mint a brand-new daily dollar
 * ceiling - a job would literally BE the way to escape the cap a chat turn obeys. The assertion is
 * therefore on the exact value threaded into {@code chat()}, not on any intermediate the code could
 * later stop using.
 *
 * <p>The same call also proves the session id handed to the chat layer is the derived one: that
 * string is what makes {@code tool_call_log}, {@code llm_call_log} and the Langfuse trace queryable
 * per job with no new plumbing, and it is also what the per-job tool ceiling counts on.
 */
class AgentJobBudgetSubjectTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theSubjectThreadedIntoChatIsTheRequesterNotASyntheticPerJobKey() {
        AgentJob job = AgentJobTestSupport.queuedJob("t_acme", "carol@example.com", 60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("done"));

        new AgentJobExecution(
                        repository,
                        chatService,
                        mock(ToolCallLogRepository.class),
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class))
                .run(job.getId(), "t_acme");

        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chatService).chat(any(TurnContext.class), turns.capture());

        assertEquals(job.getRequestedBy(), turns.getValue().subjectKey());
        assertEquals("carol@example.com", turns.getValue().subjectKey());
        assertEquals(AgentJobSession.sessionIdFor(job.getId()), turns.getValue().sessionId());
        assertEquals(job.getGoal(), turns.getValue().userMessage());

        // And it really did finish, so the assertions above are about a turn
        // that actually ran rather than one that was skipped.
        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals("done", job.getResultText());
    }
}
