package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.chat.LlmCallLog;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Prod signed-in pass 2026-10-05, bug 14: a scheduled task's run row showed no cost for a run that
 * spent tokens on the workspace's own (BYOK) key. The run row copies {@code agent_job.cost_usd},
 * and that column was never written: {@link AgentJob#succeed} was always handed {@code null}.
 */
class AgentJobCostAttributionTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static LlmCallLog call(String cost) {
        LlmCallLog row = mock(LlmCallLog.class);
        when(row.getEstimatedCostUsd()).thenReturn(cost == null ? null : new BigDecimal(cost));
        return row;
    }

    private static AgentJobExecution execution(AgentJob job, LlmCallLogService callLog) {
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(), any())).thenReturn(0L);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("e2e scheduled ok"));
        AgentJobExecution execution =
                new AgentJobExecution(
                        repository,
                        chatService,
                        toolCallLogRepository,
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class));
        if (callLog != null) {
            execution.setLlmCallLogService(callLog);
        }
        return execution;
    }

    @Test
    void aSucceededJobRecordsTheCostOfItsOwnModelCallsFromTheCallLog() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        LlmCallLogService callLog = mock(LlmCallLogService.class);
        // Two priced calls (a tool round trip) and one on a model the catalog could not price.
        List<LlmCallLog> calls = List.of(call("0.001840"), call("0.001840"), call(null));
        when(callLog.forSessionAgentWindow(
                        eq(job.getSessionId()), eq(job.getAgentId()), any(), any()))
                .thenReturn(calls);

        execution(job, callLog).run(job.getId(), AgentJobTestSupport.TENANT);

        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals(new BigDecimal("0.003680"), job.getCostUsd());
    }

    @Test
    void unknownCostStaysUnknownRatherThanBecomingZero() {
        AgentJob unpriced = AgentJobTestSupport.queuedJob(60, 15);
        LlmCallLogService callLog = mock(LlmCallLogService.class);
        List<LlmCallLog> onlyUnpriced = List.of(call(null));
        when(callLog.forSessionAgentWindow(any(), any(), any(), any())).thenReturn(onlyUnpriced);
        execution(unpriced, callLog).run(unpriced.getId(), AgentJobTestSupport.TENANT);
        assertEquals(AgentJobStatus.SUCCEEDED, unpriced.getStatus());
        assertNull(unpriced.getCostUsd());

        AgentJob noLogService = AgentJobTestSupport.queuedJob(60, 15);
        execution(noLogService, null).run(noLogService.getId(), AgentJobTestSupport.TENANT);
        assertEquals(AgentJobStatus.SUCCEEDED, noLogService.getStatus());
        assertNull(noLogService.getCostUsd());
    }

    @Test
    void aFailingCostLookupNeverFailsAFinishedJob() {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        LlmCallLogService callLog = mock(LlmCallLogService.class);
        when(callLog.forSessionAgentWindow(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("call log unavailable"));

        execution(job, callLog).run(job.getId(), AgentJobTestSupport.TENANT);

        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertNull(job.getCostUsd());
    }
}
