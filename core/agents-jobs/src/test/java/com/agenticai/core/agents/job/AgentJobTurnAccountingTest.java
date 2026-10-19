package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.durability.ZeroWorkOrphanRecoveryPolicy;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * {@code turns_used} counts turns STARTED, and the durable increment is written BEFORE the model
 * call.
 *
 * <p>This class exists because the reverse ordering silently invalidated PR-4's entire
 * no-double-charge argument, and nothing caught it: money is taken ahead of the provider attempt
 * ({@code buildChainForFreeTenant} consumes a FREE-tier message unit and takes a {@code
 * PerUserBudgetService} reservation before any provider is tried, and a SIGKILLed process runs none
 * of the refund paths), so a worker killed DURING the model call - the longest and by far the
 * likeliest interruption window - used to leave a row at {@code turns_used = 0} with no {@code
 * tool_call_log} entries. {@link ZeroWorkOrphanRecoveryPolicy} then read that as "provably zero
 * work", re-queued it, and the requester paid twice for one request.
 *
 * <p>The assertions below therefore pin the ORDER, not just the end state - an end-state assertion
 * passes under both orderings and is exactly what let the defect ship.
 */
class AgentJobTurnAccountingTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theDurableTurnMarkerIsWrittenBeforeTheModelCall() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(), any())).thenReturn(0L);

        List<String> order = new ArrayList<>();
        when(repository.incrementTurnsUsed(job.getId()))
                .thenAnswer(
                        invocation -> {
                            order.add("increment");
                            return 1;
                        });
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        invocation -> {
                            order.add("chat");
                            return Flux.just("done");
                        });

        new AgentJobExecution(
                        repository,
                        chatService,
                        toolCallLogRepository,
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class))
                .run(job.getId(), AgentJobTestSupport.TENANT);

        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals(
                List.of("increment", "chat"),
                order,
                "turns_used must be durably incremented BEFORE the model call - the spend happens"
                        + " inside the call, and a worker killed mid-call runs no refund path");
    }

    @Test
    void aJobKilledMidModelCallIsLeftLookingLikeStartedWorkNotZeroWork() {
        // The production shape of the incident, as far as a unit test can go:
        // the model call blows up the way a dying JVM's in-flight call does, and
        // the assertion is on what the ROW says afterwards - because that row is
        // the only thing the recovery policy on the next boot gets to look at.
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(), any())).thenReturn(0L);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenThrow(new IllegalStateException("connection reset by peer"));

        new AgentJobExecution(
                        repository,
                        chatService,
                        toolCallLogRepository,
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class))
                .run(job.getId(), AgentJobTestSupport.TENANT);

        verify(repository).incrementTurnsUsed(job.getId());
        assertTrue(
                job.getTurnsUsed() > 0,
                "a job whose model call was entered must not be able to look like a zero-work"
                        + " orphan afterwards");
    }
}
