package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.temporal.TemporalConfig;
import com.agenticai.core.workflow.temporal.WorkflowNodeActivitiesImpl;
import com.agenticai.core.workflow.temporal.WorkflowRunInput;
import com.agenticai.core.workflow.temporal.WorkflowRunResult;
import com.agenticai.core.workflow.temporal.WorkflowRunWorkflow;
import com.agenticai.core.workflow.temporal.WorkflowRunWorkflowImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Engine-level expression data tests that need both engines and the approval-resume leg over one real
 * {@link WorkflowNodeExecutor} + resolver + in-memory {@link RunDataStore}:
 * <ul>
 *   <li>a value written by one node is read by a later node after an approval pause (the resume is a second
 *       engine call on the same run id);</li>
 *   <li>the legacy engine and the Temporal workflow (real activities, so the store round trip crosses
 *       activity boundaries) render the same graph identically;</li>
 *   <li>what a sub_flow body can read today (pinned, see the spec's decisions section).</li>
 * </ul>
 */
class WorkflowEnginesExpressionParityTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final List<WorkflowRunNodeOutput> saved = new ArrayList<>();
    private WorkflowExecutionEngine engine;
    private WorkflowNodeExecutor executor;
    private WorkflowDefinition definition;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        RunDataStore store = new RunDataStore(PinTestSupport.runOutputRepository(saved));
        executor = WorkflowNodeExecutorPinTest.newExecutor(mock(OrchestratorService.class), mapper);
        executor.setExpressions(new WorkflowExpressionResolver(store, mock(WorkflowRunRepository.class), mapper, true, false));
        engine = new WorkflowExecutionEngine(
            mock(WorkflowRunRepository.class), mock(OrchestratorService.class), executor,
            mock(WorkflowEventPublisher.class), mapper, mock(PlatformIssueService.class),
            mock(ObjectProvider.class), mock(ObjectProvider.class),
            mock(WorkflowRunCancellationService.class), mock(WorkflowRunPauseService.class), null, null,
            new com.agenticai.core.security.concurrent.ContextPropagatingExecutor("test-branch-", 16));
        definition = new WorkflowDefinition("WF", "d", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE);
        java.lang.reflect.Field id = WorkflowDefinition.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(definition, UUID.randomUUID());
    }

    private static WorkflowNodeDto node(String id, String label, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", label));
    }

    private static WorkflowNodeDto template(String id, String label, String template) {
        return node(id, label, "code", Map.of("operation", "template", "template", template));
    }

    private static WorkflowEdgeDto edge(String from, String to) {
        return new WorkflowEdgeDto(from + "-" + to, from, to, null, null);
    }

    private String storedOutput(UUID runId, String nodeId) {
        return saved.stream().filter(o -> o.getRunId().equals(runId) && o.getNodeId().equals(nodeId))
            .map(WorkflowRunNodeOutput::getOutputText).findFirst().orElseThrow();
    }

    // ---- approval resume reads outputs written before the pause

    @Test
    void anExpressionAfterAnApprovalPauseReadsAnOutputWrittenBeforeIt() throws Exception {
        WorkflowGraphDto firstLeg = new WorkflowGraphDto(
            List.of(node("t1", "Start", "trigger", Map.of()),
                template("b1", "Before", "{\"tier\":\"gold\",\"n\":2}")),
            List.of(edge("t1", "b1")));
        WorkflowRun run = engine.execute(definition, firstLeg, null, "{\"price\":10}", "ann", null, List.of());
        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());

        // The run parks at an approval node between "Before" and "After"; approving re-enters at "After" with
        // the input recorded at the halt, as a second engine call on the same run (and the same run id).
        WorkflowGraphDto fullGraph = new WorkflowGraphDto(
            List.of(node("t1", "Start", "trigger", Map.of()),
                template("b1", "Before", "{\"tier\":\"gold\",\"n\":2}"),
                template("a1", "After",
                    "tier={{ $node[\"Before\"].tier }} price={{ $trigger.price }} n={{ $node[\"Before\"].n * 3 }}")),
            List.of(edge("t1", "b1"), edge("b1", "a1")));
        List<NodeTraceEntry> prior = mapper.readValue(run.getTraceJson(), new TypeReference<List<NodeTraceEntry>>() {});

        WorkflowRun resumed = engine.resume(definition, fullGraph, run, prior, "a1", "{\"tier\":\"gold\",\"n\":2}", "ann");

        assertEquals(WorkflowRunStatus.COMPLETED, resumed.getStatus(), resumed.getErrorMessage());
        assertEquals("tier=gold price=10 n=6", storedOutput(run.getId(), "a1"));
    }

    // ---- legacy engine and Temporal workflow agree

    @Test
    void theLegacyEngineAndTheTemporalWorkflowRenderTheSameGraphIdentically() throws Exception {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(node("t1", "Start", "trigger", Map.of()),
                template("b1", "Before", "{\"tier\":\"gold\",\"n\":2}"),
                template("c1", "Combine",
                    "price={{ $node[\"Start\"].price }} tier={{ $node[\"Before\"].tier }} n={{ $node[\"Before\"].n * 3 }}")),
            List.of(edge("t1", "b1"), edge("b1", "c1")));
        String input = "{\"price\":10}";

        WorkflowRun legacy = engine.execute(definition, graph, null, input, "ann", null, List.of());
        assertEquals(WorkflowRunStatus.COMPLETED, legacy.getStatus(), legacy.getErrorMessage());

        ExecutorService pool = Executors.newCachedThreadPool();
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        try {
            WorkflowRunRepository repo = mock(WorkflowRunRepository.class);
            when(repo.findById(any(UUID.class))).thenAnswer(inv ->
                Optional.of(new WorkflowRun(inv.getArgument(0), definition.getId(), "WF", "ann")));
            WorkflowNodeActivitiesImpl activities = new WorkflowNodeActivitiesImpl(
                executor, repo, mock(WorkflowEventPublisher.class), mapper, pool);
            ReflectionTestUtils.setField(activities, "heartbeatIntervalSeconds", 5L);
            Worker worker = env.newWorker(TemporalConfig.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(WorkflowRunWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            env.start();

            String temporalRunId = UUID.randomUUID().toString();
            WorkflowRunInput in = new WorkflowRunInput(
                temporalRunId, definition.getId().toString(), "WF", graph, null, input, "ann",
                "tenant-1", "user-1", Set.of(), 50, 30, 0, null);
            WorkflowRunWorkflow stub = env.getWorkflowClient().newWorkflowStub(
                WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());
            WorkflowRunResult result = stub.run(in);

            assertEquals("COMPLETED", result.status(), result.errorMessage());
            String expected = "price=10 tier=gold n=6";
            assertEquals(expected, storedOutput(legacy.getId(), "c1"));
            assertEquals(expected, storedOutput(UUID.fromString(temporalRunId), "c1"),
                "a value written by one activity is read by a later one");
        } finally {
            env.close();
            pool.shutdownNow();
        }
    }

    // ---- sub_flow: what the body can read (deviation from spec 4.2, recorded in its decisions)

    @Test
    void aSubFlowBodyShareTheRunSoItReadsTheParentsOutputsAndTheWholeRunTrigger() throws Exception {
        Map<String, Object> body = Map.of(
            "nodes", List.of(Map.of("id", "sb1", "type", "code", "position", Map.of(),
                "data", Map.of("label", "Inner", "config", Map.of(
                    "operation", "template", "template", "item={{ $item }}|trigger={{ toJson($trigger) }}|start={{ toJson($node[\"Start\"]) }}")))),
            "edges", List.of());
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(node("t1", "Start", "trigger", Map.of()), node("s1", "Sub", "sub_flow", Map.of("body", body))),
            List.of(edge("t1", "s1")));

        WorkflowRun run = engine.execute(definition, graph, null, "[\"a\",\"b\"]", "ann", null, List.of());

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus(), run.getErrorMessage());
        List<NodeTraceEntry> trace = mapper.readValue(run.getTraceJson(), new TypeReference<List<NodeTraceEntry>>() {});
        List<String> inner = trace.stream().filter(e -> e.nodeId().equals("sb1")).map(NodeTraceEntry::outputJson).toList();
        assertEquals(List.of(
            "item=a|trigger=[\"a\",\"b\"]|start=[\"a\",\"b\"]",
            "item=b|trigger=[\"a\",\"b\"]|start=[\"a\",\"b\"]"), inner,
            "the per-item input is $item; $trigger and $node are the enclosing run's (there is no child run)");
        assertTrue(inner.size() == 2);
    }
}
