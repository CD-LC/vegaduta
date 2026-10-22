package com.agenticai.core.workflow.guidance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.dataset.EvalRun;
import com.agenticai.core.quality.dataset.EvalRunRepository;
import com.agenticai.core.quality.dataset.EvalRunStatus;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunRepository;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

class WorkflowInsightsServiceTest {

    private static final UUID WF = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final ObjectMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder()
                    .configureForJackson2()
                    .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    private WorkflowRunRepository runs;
    private EvalRunRepository evals;
    private WorkflowInsightsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runs = mock(WorkflowRunRepository.class);
        evals = mock(EvalRunRepository.class);
        ObjectProvider<EvalRunRepository> ep = mock(ObjectProvider.class);
        when(ep.getIfAvailable()).thenReturn(evals);
        service = new WorkflowInsightsService(runs, MAPPER, ep, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NodeTraceEntry entry(String nodeId, String status, long ms, String cost) {
        Instant start = NOW.minusSeconds(3600);
        NodeTraceEntry e =
                new NodeTraceEntry(
                        nodeId,
                        "agent",
                        nodeId,
                        status,
                        "s",
                        start,
                        start.plusMillis(ms),
                        List.of());
        return cost == null ? e : e.withUsage(10L, 5L, new BigDecimal(cost));
    }

    private static WorkflowRun run(WorkflowRunStatus status, long runMs, List<NodeTraceEntry> trace)
            throws Exception {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), WF, "wf", "u");
        run.complete(status, MAPPER.writeValueAsString(trace), null, null, null);
        java.lang.reflect.Field started = WorkflowRun.class.getDeclaredField("startedAt");
        started.setAccessible(true);
        started.set(run, NOW.minusSeconds(7200));
        java.lang.reflect.Field completed = WorkflowRun.class.getDeclaredField("completedAt");
        completed.setAccessible(true);
        completed.set(run, NOW.minusSeconds(7200).plusMillis(runMs));
        return run;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesRunsAndNodeVisitsIncludingLoopRepeats() throws Exception {
        List<WorkflowRun> data = new ArrayList<>();
        // run 1: node a visited 3 times (loop), one error
        data.add(
                run(
                        WorkflowRunStatus.COMPLETED,
                        1000,
                        List.of(
                                entry("a", "OK", 100, "0.010"),
                                entry("a", "OK", 300, "0.030"),
                                entry("a", "ERROR", 200, null))));
        // run 2: failed
        data.add(run(WorkflowRunStatus.FAILED, 3000, List.of(entry("a", "OK", 400, "0.020"))));
        when(runs.findByWorkflowIdAndStartedAtAfterOrderByStartedAtDesc(eq(WF), any(), any()))
                .thenReturn(data);

        Map<String, Object> summary = service.summary(WF, null, 30);

        Map<String, Object> wf = (Map<String, Object>) summary.get("workflow");
        assertEquals(2, wf.get("runs30d"));
        assertEquals(0.5, wf.get("successRate30d"));
        assertEquals(2000L, wf.get("p50DurationMs"));
        assertNull(wf.get("passRate"), "workflows have no evals of their own");

        Map<String, Object> a =
                (Map<String, Object>) ((Map<String, Object>) summary.get("nodes")).get("a");
        assertEquals(4, a.get("visits"));
        assertEquals(2, a.get("runs"));
        assertEquals(0.75, a.get("successRate"));
        assertEquals(250L, a.get("p50DurationMs"));
        assertEquals(
                new BigDecimal("0.020000"),
                a.get("avgCostUsd"),
                "mean over visits that have a cost");
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentNodesGetTheLatestEvalScoreEvenWithoutRuns() {
        UUID agentId = UUID.randomUUID();
        EvalRun eval = mock(EvalRun.class);
        when(eval.getOverallScore()).thenReturn(0.87);
        when(evals.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(
                        agentId, EvalRunStatus.SUCCEEDED))
                .thenReturn(Optional.of(eval));
        when(runs.findByWorkflowIdAndStartedAtAfterOrderByStartedAtDesc(eq(WF), any(), any()))
                .thenReturn(List.of());
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                new WorkflowNodeDto(
                                        "n1",
                                        "agent",
                                        Map.of(),
                                        Map.of("config", Map.of("agentId", agentId.toString())))),
                        List.of());

        Map<String, Object> nodes =
                (Map<String, Object>) service.summary(WF, graph, 30).get("nodes");

        assertEquals(0.87, ((Map<String, Object>) nodes.get("n1")).get("evalScore"));
    }

    @Test
    void resultsAreCachedPerWindow() {
        when(runs.findByWorkflowIdAndStartedAtAfterOrderByStartedAtDesc(eq(WF), any(), any()))
                .thenReturn(List.of());
        service.summary(WF, null, 30);
        service.summary(WF, null, 30);
        service.summary(WF, null, 7);
        verify(runs, times(2))
                .findByWorkflowIdAndStartedAtAfterOrderByStartedAtDesc(eq(WF), any(), any());
    }

    @Test
    void medianAndRateHelpers() {
        assertNull(WorkflowInsightsService.median(List.of()));
        assertEquals(2L, WorkflowInsightsService.median(List.of(3L, 1L, 2L)));
        assertNull(WorkflowInsightsService.rate(0, 0));
        assertEquals(0.3333, WorkflowInsightsService.rate(1, 3));
    }
}
