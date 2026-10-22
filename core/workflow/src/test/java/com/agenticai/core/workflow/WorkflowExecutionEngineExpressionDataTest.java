package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The real legacy {@link WorkflowExecutionEngine} + real {@link WorkflowNodeExecutor} + real
 * resolver over an in-memory {@link RunDataStore}: what a saved graph's expressions actually read.
 * Covers loop {@code $item} / {@code $index} in the body and, for every engine-level container
 * (loop, parallel, fan_out, sub_flow), that {@code $node["<container label>"]} reads the
 * container's merged output downstream.
 */
class WorkflowExecutionEngineExpressionDataTest {

    private final ObjectMapper mapper = JsonMappers.withJavaTime();
    private final List<WorkflowRunNodeOutput> saved = new ArrayList<>();
    private WorkflowExecutionEngine engine;
    private WorkflowDefinition definition;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        RunDataStore store = new RunDataStore(PinTestSupport.runOutputRepository(saved));
        WorkflowNodeExecutor executor =
                WorkflowNodeExecutorPinTest.newExecutor(mock(OrchestratorService.class), mapper);
        executor.setExpressions(
                new WorkflowExpressionResolver(
                        store, mock(WorkflowRunRepository.class), mapper, true, false));
        engine =
                new WorkflowExecutionEngine(
                        mock(WorkflowRunRepository.class),
                        mock(OrchestratorService.class),
                        executor,
                        mock(WorkflowEventPublisher.class),
                        mapper,
                        mock(PlatformIssueService.class),
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
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
    }

    private static WorkflowNodeDto node(
            String id, String label, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", label));
    }

    private static WorkflowNodeDto template(String id, String label, String template) {
        return node(id, label, "code", Map.of("operation", "template", "template", template));
    }

    private static WorkflowNodeDto upper(String id, String label) {
        return node(id, label, "code", Map.of("operation", "uppercase"));
    }

    private static WorkflowEdgeDto edge(String from, String to) {
        return new WorkflowEdgeDto(from + "-" + to, from, to, null, null);
    }

    private WorkflowRun run(WorkflowGraphDto graph, String input) {
        return engine.execute(definition, graph, null, input, "ann", null, List.of());
    }

    private List<NodeTraceEntry> trace(WorkflowRun run) throws Exception {
        return mapper.readValue(run.getTraceJson(), new TypeReference<List<NodeTraceEntry>>() {});
    }

    private static NodeTraceEntry entry(List<NodeTraceEntry> trace, String nodeId) {
        return trace.stream().filter(e -> e.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    @Test
    void loopBodyReadsItemAndIndexAndTheLoopNodeOutputIsReadableDownstream() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t1", "Start", "trigger", Map.of()),
                                node("l1", "Each", "loop", Map.of()),
                                template("b1", "Body", "{{ $item }}-{{ $index }}"),
                                template(
                                        "a1",
                                        "After",
                                        "all={{ toJson($node[\"Each\"]) }} second={{ $node[\"Each\"][1] }}")),
                        List.of(edge("t1", "l1"), edge("l1", "b1"), edge("b1", "a1")));

        WorkflowRun run = run(graph, "[\"a\",\"b\"]");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        List<NodeTraceEntry> trace = trace(run);
        List<NodeTraceEntry> bodies = trace.stream().filter(e -> e.nodeId().equals("b1")).toList();
        assertEquals(
                List.of("a-0", "b-1"),
                bodies.stream().map(NodeTraceEntry::outputJson).toList(),
                "$item / $index are bound per iteration");
        assertEquals(
                "all=[\"a-0\",\"b-1\"] second=b-1",
                entry(trace, "a1").outputJson(),
                "$node[\"Each\"] is the loop's merged output");
        // The body keeps one run-data row per iteration (seq); the loop node has its own single
        // row.
        assertEquals(
                List.of(0, 1),
                saved.stream()
                        .filter(o -> o.getNodeId().equals("b1"))
                        .map(WorkflowRunNodeOutput::getSeq)
                        .sorted()
                        .toList());
        assertEquals(1, saved.stream().filter(o -> o.getNodeId().equals("l1")).count());
    }

    @Test
    void loopItemsAreReadableAsTheWholeIterationSet() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t1", "Start", "trigger", Map.of()),
                                node("l1", "Each", "loop", Map.of()),
                                template("b1", "Body", "{{ $index }}/{{ count($items) }}")),
                        List.of(edge("t1", "l1"), edge("l1", "b1")));

        WorkflowRun run = run(graph, "[\"x\",\"y\",\"z\"]");

        assertEquals(
                List.of("0/3", "1/3", "2/3"),
                trace(run).stream()
                        .filter(e -> e.nodeId().equals("b1"))
                        .map(NodeTraceEntry::outputJson)
                        .toList());
    }

    @Test
    void parallelNodeOutputIsReadableDownstream() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t1", "Start", "trigger", Map.of()),
                                node("p1", "Fan", "parallel", Map.of()),
                                upper("x1", "BranchA"),
                                upper("x2", "BranchB"),
                                template("j1", "Join", "{{ toJson($node[\"Fan\"]) }}")),
                        List.of(
                                edge("t1", "p1"),
                                edge("p1", "x1"),
                                edge("p1", "x2"),
                                edge("x1", "j1"),
                                edge("x2", "j1")));

        WorkflowRun run = run(graph, "hi");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        assertEquals("[\"HI\",\"HI\"]", entry(trace(run), "j1").outputJson());
    }

    @Test
    void fanOutNodeOutputIsReadableDownstream() throws Exception {
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t1", "Start", "trigger", Map.of()),
                                node("f1", "Per row", "fan_out", Map.of()),
                                upper("w1", "Worker"),
                                template("a1", "After", "{{ toJson($node[\"Per row\"]) }}")),
                        List.of(edge("t1", "f1"), edge("f1", "w1"), edge("w1", "a1")));

        WorkflowRun run = run(graph, "[\"a\",\"b\",\"c\"]");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        assertEquals("[\"A\",\"B\",\"C\"]", entry(trace(run), "a1").outputJson());
    }

    @Test
    void subFlowNodeOutputIsReadableDownstream() throws Exception {
        Map<String, Object> body =
                Map.of(
                        "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "sb1",
                                                "type",
                                                "code",
                                                "position",
                                                Map.of(),
                                                "data",
                                                Map.of(
                                                        "label",
                                                        "Inner",
                                                        "config",
                                                        Map.of("operation", "uppercase")))),
                        "edges", List.of());
        WorkflowGraphDto graph =
                new WorkflowGraphDto(
                        List.of(
                                node("t1", "Start", "trigger", Map.of()),
                                node("s1", "Sub", "sub_flow", Map.of("body", body)),
                                template("a1", "After", "{{ toJson($node[\"Sub\"]) }}")),
                        List.of(edge("t1", "s1"), edge("s1", "a1")));

        WorkflowRun run = run(graph, "[\"a\",\"b\"]");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        assertEquals("[\"A\",\"B\"]", entry(trace(run), "a1").outputJson());
        assertTrue(saved.stream().anyMatch(o -> o.getNodeId().equals("s1")));
    }
}
