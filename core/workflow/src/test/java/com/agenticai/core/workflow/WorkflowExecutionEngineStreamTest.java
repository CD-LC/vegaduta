package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.agenticai.core.workflow.stream.NodeStreamContext;
import com.agenticai.core.workflow.stream.WorkflowRunStreamHub;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/** Phase 3: the engine's run-level and node-level events on the live stream. */
class WorkflowExecutionEngineStreamTest {

    private record Published(String type, String nodeId, Map<String, ?> data) {}

    private final List<Published> published = new ArrayList<>();
    private WorkflowRunStreamHub hub;
    private WorkflowNodeExecutor nodeExecutor;
    private WorkflowExecutionEngine engine;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
        hub =
                new WorkflowRunStreamHub(mapper) {
                    @Override
                    public void publish(
                            String runId, String type, String nodeId, Map<String, ?> data) {
                        published.add(new Published(type, nodeId, data));
                        super.publish(runId, type, nodeId, data);
                    }
                };
        nodeExecutor = mock(WorkflowNodeExecutor.class);
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
                        mock(WorkflowRunCancellationService.class),
                        mock(WorkflowRunPauseService.class),
                        null,
                        hub,
                        new com.agenticai.core.security.concurrent.ContextPropagatingExecutor(
                                "test-branch-", 16));
    }

    @AfterEach
    void tearDown() {
        NodeStreamContext.clear();
    }

    private static WorkflowNodeDto node(String id, String type) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", Map.of(), "label", id));
    }

    private static WorkflowDefinition definition() throws ReflectiveOperationException {
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        "WF", "d", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE);
        java.lang.reflect.Field id = WorkflowDefinition.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(definition, UUID.randomUUID());
        return definition;
    }

    private static WorkflowGraphDto graph(String secondType) {
        return new WorkflowGraphDto(
                List.of(node("t1", "trigger"), node("n2", secondType)),
                List.of(new WorkflowEdgeDto("e1", "t1", "n2", null, null)));
    }

    @Test
    void aRunEmitsStartedNodeEventsCompletedEntriesAndFinished() throws Exception {
        List<Boolean> sinkLiveDuringExecution = new ArrayList<>();
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
                        inv -> {
                            WorkflowNodeDto n = inv.getArgument(3);
                            sinkLiveDuringExecution.add(NodeStreamContext.current().isLive());
                            NodeStreamContext.current().token("hello from " + n.id());
                            return WorkflowNodeExecutor.NodeExecutionResult.ok(
                                    "out-" + n.id(), "ran", null);
                        });
        WorkflowDefinition def = definition();
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), def.getId(), def.getName(), "u");

        engine.continueRun(def, graph("code"), run, null, "in", "u", List.of());

        List<String> types = published.stream().map(Published::type).toList();
        assertEquals("run.started", types.get(0));
        assertEquals("run.finished", types.get(types.size() - 1));
        assertEquals(List.of(true, true), sinkLiveDuringExecution);
        assertEquals(2, types.stream().filter("node.started"::equals).count());
        assertTrue(types.contains("node.token"), types.toString());
        List<Published> completed =
                published.stream().filter(p -> p.type().equals("node.completed")).toList();
        assertEquals(2, completed.size());
        NodeTraceEntry last = (NodeTraceEntry) completed.get(1).data().get("entry");
        assertEquals("out-n2", last.outputJson());
        assertNotNull(completed.get(1).data().get("runTotals"));
        assertEquals(
                false, NodeStreamContext.current().isLive(), "sink must be unbound after the node");
    }

    @Test
    void anApprovalHaltIsPublishedAsAwaitingWithTheRequestId() throws Exception {
        String requestId = UUID.randomUUID().toString();
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
                        inv -> {
                            WorkflowNodeDto n = inv.getArgument(3);
                            return "approval".equals(n.type())
                                    ? WorkflowNodeExecutor.NodeExecutionResult.halted(
                                            WorkflowRunStatus.AWAITING_APPROVAL,
                                            "Awaiting human approval (request " + requestId + ").")
                                    : WorkflowNodeExecutor.NodeExecutionResult.ok("x", "ok", null);
                        });
        WorkflowDefinition def = definition();
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), def.getId(), def.getName(), "u");

        engine.continueRun(def, graph("approval"), run, null, "in", "u", List.of());

        Published awaiting =
                published.stream()
                        .filter(p -> p.type().equals("run.awaiting"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("AWAITING_APPROVAL", awaiting.data().get("status"));
        assertEquals("n2", awaiting.data().get("nodeId"));
        assertEquals(requestId, awaiting.data().get("requestId"));
        assertTrue(published.stream().noneMatch(p -> p.type().equals("run.finished")));
    }
}
