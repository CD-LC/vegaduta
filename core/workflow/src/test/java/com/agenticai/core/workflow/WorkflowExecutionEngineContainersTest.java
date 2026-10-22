package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Characterisation of the engine-level containers (loop, parallel, fan_out, sub_flow) and the
 * between-node Stop / Pause checkpoints against a stubbed {@link WorkflowNodeExecutor}: trace
 * labels and summaries, merged outputs, error-edge routing, halts, the step ceiling. Pins today's
 * behavior so the traversal can be split into per-construct step handlers without a visible change.
 */
class WorkflowExecutionEngineContainersTest {

    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.withJavaTime();
    private final List<String> inputsSeen =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private WorkflowNodeExecutor nodeExecutor;
    private WorkflowRunCancellationService cancellation;
    private WorkflowRunPauseService pause;
    private WorkflowExecutionEngine engine;
    private WorkflowDefinition definition;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        nodeExecutor = mock(WorkflowNodeExecutor.class);
        cancellation = mock(WorkflowRunCancellationService.class);
        pause = mock(WorkflowRunPauseService.class);
        engine =
                new WorkflowExecutionEngine(
                        mock(WorkflowRunRepository.class),
                        mock(OrchestratorService.class),
                        nodeExecutor,
                        mock(WorkflowEventPublisher.class),
                        mapper,
                        mock(PlatformIssueService.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        cancellation,
                        pause,
                        null,
                        null,
                        new com.agenticai.core.security.concurrent.ContextPropagatingExecutor(
                                "test-branch-", 16));
        definition =
                new WorkflowDefinition(
                        "WF", "d", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE);
        java.lang.reflect.Field id = WorkflowDefinition.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(definition, UUID.randomUUID());
        // Default: every node echoes "<id>(<input>)"; a node with config fail=true throws;
        // haltFailed returns FAILED.
        when(nodeExecutor.executeNode(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(WorkflowNodeDto.class),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenAnswer(
                        invocation -> {
                            WorkflowNodeDto node = invocation.getArgument(3);
                            String input = invocation.getArgument(4);
                            inputsSeen.add(node.id() + "<-" + input);
                            if ("trigger".equals(node.type())
                                    && input != null
                                    && input.startsWith("[")) {
                                // A JSON-array run payload passes through the trigger, so a loop /
                                // fan_out reads it as its items.
                                return WorkflowNodeExecutor.NodeExecutionResult.ok(
                                        input, "ran " + node.id(), null);
                            }
                            if (Boolean.TRUE.equals(node.config().get("fail"))) {
                                throw new IllegalStateException("boom");
                            }
                            if (Boolean.TRUE.equals(node.config().get("haltFailed"))) {
                                return new WorkflowNodeExecutor.NodeExecutionResult(
                                        input,
                                        "FAILED",
                                        "halted " + node.id(),
                                        null,
                                        WorkflowRunStatus.FAILED,
                                        List.of(),
                                        null,
                                        null);
                            }
                            return WorkflowNodeExecutor.NodeExecutionResult.ok(
                                    node.id() + "(" + input + ")", "ran " + node.id(), null);
                        });
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    private static WorkflowEdgeDto edge(String from, String to) {
        return new WorkflowEdgeDto(from + "-" + to, from, to, null, null);
    }

    private static WorkflowEdgeDto errorEdge(String from, String to) {
        return new WorkflowEdgeDto(from + "-" + to, from, to, "error", null);
    }

    private WorkflowRun run(WorkflowGraphDto graph, String input) {
        return engine.execute(definition, graph, null, input, "ann", null, List.of());
    }

    private List<NodeTraceEntry> trace(WorkflowRun run) throws Exception {
        return mapper.readValue(run.getTraceJson(), new TypeReference<List<NodeTraceEntry>>() {});
    }

    private static List<String> summaries(List<NodeTraceEntry> trace, String nodeId) {
        return trace.stream()
                .filter(e -> e.nodeId().equals(nodeId))
                .map(NodeTraceEntry::summary)
                .toList();
    }

    @Test
    void loopRepeatsTheBodyPerItemCapsAtMaxIterationsAndHandsTheMergedArrayOn() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("l", "loop", Map.of("maxIterations", 2)),
                                node("b", "code", Map.of()),
                                node("a", "code", Map.of())),
                        List.of(edge("t", "l"), edge("l", "b"), edge("b", "a")));

        WorkflowRun run = run(graph, "[\"x\",\"y\",\"z\"]");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        List<NodeTraceEntry> trace = trace(run);
        assertEquals(
                List.of("t", "b", "b", "l", "a"),
                trace.stream().map(NodeTraceEntry::nodeId).toList());
        assertEquals(
                List.of("b (loop 1/2)", "b (loop 2/2)"),
                trace.stream()
                        .filter(e -> e.nodeId().equals("b"))
                        .map(NodeTraceEntry::label)
                        .toList());
        assertEquals(List.of("Repeated 'b' 2 of 3 item(s) (capped at 2)."), summaries(trace, "l"));
        assertTrue(inputsSeen.contains("a<-[\"b(x)\",\"b(y)\"]"), inputsSeen.toString());
    }

    @Test
    void loopBodyFailureWithAnErrorEdgeRoutesTheRunDownTheEdge() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("l", "loop", Map.of()),
                                node("b", "code", Map.of("fail", true, "retryAttempts", 0)),
                                node("h", "code", Map.of())),
                        List.of(edge("t", "l"), edge("l", "b"), errorEdge("b", "h")));

        WorkflowRun run = run(graph, "[\"x\",\"y\"]");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        List<NodeTraceEntry> trace = trace(run);
        assertEquals(
                List.of("Item failed and the run was routed to the error branch: boom"),
                summaries(trace, "b"));
        assertEquals(
                "b (loop 1/2)",
                trace.stream()
                        .filter(e -> e.nodeId().equals("b"))
                        .findFirst()
                        .orElseThrow()
                        .label());
        assertTrue(
                inputsSeen.stream()
                        .anyMatch(
                                s -> s.startsWith("h<-{") && s.contains("\"failedNodeId\":\"b\"")),
                inputsSeen.toString());
        assertTrue(
                summaries(trace, "l").isEmpty(),
                "the loop node never completes when its body routed away");
    }

    @Test
    void loopBodyFailureWithoutAnErrorEdgeFailsTheRunAttributedToTheBody() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("l", "loop", Map.of()),
                                node("b", "code", Map.of("fail", true, "retryAttempts", 0))),
                        List.of(edge("t", "l"), edge("l", "b")));

        WorkflowRun run = run(graph, "[\"x\"]");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertEquals("boom", run.getErrorMessage());
        NodeTraceEntry last = trace(run).get(trace(run).size() - 1);
        assertEquals("b", last.nodeId());
        assertEquals("ERROR", last.status());
    }

    @Test
    void loopObservesStopRunBetweenIterations() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        // trigger, loop node, iteration 1 -> not cancelled; iteration 2 -> cancelled.
        when(cancellation.isCancellationRequested(any()))
                .thenAnswer(i -> checks.incrementAndGet() >= 4);
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("l", "loop", Map.of()),
                                node("b", "code", Map.of())),
                        List.of(edge("t", "l"), edge("l", "b")));

        WorkflowRun run = run(graph, "[\"x\",\"y\",\"z\"]");

        assertEquals(WorkflowRunStatus.CANCELLED, run.getStatus());
        List<NodeTraceEntry> trace = trace(run);
        NodeTraceEntry last = trace.get(trace.size() - 1);
        assertEquals("CANCELLED", last.status());
        assertEquals("b", last.nodeId());
        assertEquals("Run cancelled by user request during loop iteration 2/3.", last.summary());
        assertEquals(
                1,
                trace.stream()
                        .filter(e -> e.nodeId().equals("b") && e.status().equals("OK"))
                        .count());
    }

    @Test
    void loopStopsAtTheStepCeiling() throws Exception {
        definition.setExecutionLimits(3, 30);
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("l", "loop", Map.of()),
                                node("b", "code", Map.of())),
                        List.of(edge("t", "l"), edge("l", "b")));

        WorkflowRun run = run(graph, "[\"x\",\"y\",\"z\"]");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getErrorMessage().contains("maximum of 3"), run.getErrorMessage());
    }

    @Test
    void loopHaltingFailedCompletesTheRunAsFailedWithTheNodesSummary() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("l", "loop", Map.of()),
                                node("b", "code", Map.of("haltFailed", true))),
                        List.of(edge("t", "l"), edge("l", "b")));

        WorkflowRun run = run(graph, "[\"x\",\"y\"]");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertEquals("halted b", run.getErrorMessage());
        assertEquals(
                1,
                trace(run).stream().filter(e -> e.nodeId().equals("b")).count(),
                "halts end the loop");
    }

    @Test
    void parallelRunsEveryBranchAgainstTheSameInputAndJoinsOffTheLastBranch() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("p", "parallel", Map.of()),
                                node("x", "code", Map.of()),
                                node("y", "code", Map.of()),
                                node("j", "code", Map.of())),
                        List.of(
                                edge("t", "p"),
                                edge("p", "x"),
                                edge("p", "y"),
                                edge("x", "j"),
                                edge("y", "j")));

        WorkflowRun run = run(graph, "hi");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        List<NodeTraceEntry> trace = trace(run);
        assertEquals(
                List.of("x (parallel 1/2)"),
                trace.stream()
                        .filter(e -> e.nodeId().equals("x"))
                        .map(NodeTraceEntry::label)
                        .toList());
        assertEquals(List.of("Fanned out to 2 branch(es), all completed."), summaries(trace, "p"));
        assertTrue(
                inputsSeen.contains("x<-t(hi)") && inputsSeen.contains("y<-t(hi)"),
                inputsSeen.toString());
        assertTrue(inputsSeen.contains("j<-[\"x(t(hi))\",\"y(t(hi))\"]"), inputsSeen.toString());
    }

    @Test
    void parallelBranchFailureRoutesToItsErrorEdgeOrFailsTheRun() throws Exception {
        WorkflowGraphDto routed =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("p", "parallel", Map.of()),
                                node("x", "code", Map.of("fail", true, "retryAttempts", 0)),
                                node("y", "code", Map.of()),
                                node("h", "code", Map.of())),
                        List.of(
                                edge("t", "p"),
                                edge("p", "x"),
                                edge("p", "y"),
                                errorEdge("x", "h")));

        WorkflowRun run = run(routed, "hi");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        assertEquals(
                List.of("Parallel branch failed and the run was routed to the error branch: boom"),
                summaries(trace(run), "x"));
        assertTrue(inputsSeen.stream().anyMatch(s -> s.startsWith("h<-{")), inputsSeen.toString());

        inputsSeen.clear();
        WorkflowGraphDto unrouted =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("p", "parallel", Map.of()),
                                node("x", "code", Map.of("fail", true, "retryAttempts", 0)),
                                node("y", "code", Map.of())),
                        List.of(edge("t", "p"), edge("p", "x"), edge("p", "y")));
        WorkflowRun failed = run(unrouted, "hi");
        assertEquals(WorkflowRunStatus.FAILED, failed.getStatus());
        assertEquals(
                "p",
                trace(failed).get(trace(failed).size() - 1).nodeId(),
                "attributed to the Parallel node");
    }

    @Test
    void parallelBranchHaltFailsTheRun() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("p", "parallel", Map.of()),
                                node("x", "code", Map.of("haltFailed", true)),
                                node("y", "code", Map.of())),
                        List.of(edge("t", "p"), edge("p", "x"), edge("p", "y")));

        WorkflowRun run = run(graph, "hi");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertEquals("halted x", run.getErrorMessage());
    }

    @Test
    void fanOutItemFailureRoutesToTheWorkersErrorEdge() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("f", "fan_out", Map.of()),
                                node("w", "code", Map.of("fail", true, "retryAttempts", 0)),
                                node("h", "code", Map.of())),
                        List.of(edge("t", "f"), edge("f", "w"), errorEdge("w", "h")));

        WorkflowRun run = run(graph, "[\"a\",\"b\"]");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        List<String> workerSummaries = summaries(trace(run), "w");
        assertEquals(1, workerSummaries.size());
        assertTrue(
                workerSummaries
                        .get(0)
                        .startsWith(
                                "A fan-out item failed and the run was routed to the error branch:"));
    }

    @Test
    void fanOutObservesStopRunBeforeItemsStart() throws Exception {
        when(cancellation.isCancellationRequested(any())).thenReturn(false, false, true);
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("f", "fan_out", Map.of("maxConcurrency", 1)),
                                node("w", "code", Map.of())),
                        List.of(edge("t", "f"), edge("f", "w")));

        WorkflowRun run = run(graph, "[\"a\",\"b\"]");

        // Every item either ran or declined; whichever mix occurred, a Stop observed mid-fan-out
        // outranks the rest.
        assertTrue(
                run.getStatus() == WorkflowRunStatus.CANCELLED
                        || run.getStatus() == WorkflowRunStatus.COMPLETED);
        if (run.getStatus() == WorkflowRunStatus.CANCELLED) {
            assertTrue(run.getTraceJson().contains(" during fan-out after "), run.getTraceJson());
        }
    }

    @Test
    void subFlowWalksTheWholeBodyPerItem() throws Exception {
        Map<String, Object> body =
                Map.of(
                        "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "s1",
                                                "type",
                                                "code",
                                                "position",
                                                Map.of(),
                                                "data",
                                                Map.of("label", "s1", "config", Map.of())),
                                        Map.of(
                                                "id",
                                                "s2",
                                                "type",
                                                "code",
                                                "position",
                                                Map.of(),
                                                "data",
                                                Map.of("label", "s2", "config", Map.of()))),
                        "edges", List.of(Map.of("id", "e", "source", "s1", "target", "s2")));
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t", "trigger", Map.of()),
                                node("sf", "sub_flow", Map.of("body", body)),
                                node("a", "code", Map.of())),
                        List.of(edge("t", "sf"), edge("sf", "a")));

        WorkflowRun run = run(graph, "[\"x\",\"y\"]");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        List<NodeTraceEntry> trace = trace(run);
        assertEquals(
                List.of("s1 (sub-flow 1/2)", "s1 (sub-flow 2/2)"),
                trace.stream()
                        .filter(e -> e.nodeId().equals("s1"))
                        .map(NodeTraceEntry::label)
                        .toList());
        assertEquals(List.of("Ran the sub-flow body over 2 of 2 item(s)."), summaries(trace, "sf"));
        assertTrue(inputsSeen.contains("a<-[\"s2(s1(x))\",\"s2(s1(y))\"]"), inputsSeen.toString());
    }

    @Test
    void anOperatorPauseParksTheRunAtTheNextNodeBoundary() throws Exception {
        when(pause.isPauseRequested(any())).thenReturn(false, true);
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(node("t", "trigger", Map.of()), node("c", "code", Map.of())),
                        List.of(edge("t", "c")));

        WorkflowRun run = run(graph, "hi");

        assertEquals(WorkflowRunStatus.PAUSED, run.getStatus());
        List<NodeTraceEntry> trace = trace(run);
        assertEquals("PAUSED", trace.get(trace.size() - 1).status());
        assertEquals("c", trace.get(trace.size() - 1).nodeId());
        assertFalse(
                inputsSeen.stream().anyMatch(s -> s.startsWith("c<-")),
                "the paused node has not run");
    }
}
