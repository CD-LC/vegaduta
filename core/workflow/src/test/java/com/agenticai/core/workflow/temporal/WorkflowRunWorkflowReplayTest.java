package com.agenticai.core.workflow.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Replay determinism of the container-output change (spec 4.3): the extra {@code recordContainerOutput}
 * activity sits behind {@code Workflow.getVersion}. A history recorded by this code must replay on this code,
 * and its version marker must be there - removing the guard makes the marker unmatched and replay throws.
 * (A history recorded BEFORE the change is not available as a fixture; for those the SDK returns
 * DEFAULT_VERSION from {@code getVersion}, which skips the activity.)
 */
class WorkflowRunWorkflowReplayTest {

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TemporalConfig.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(WorkflowRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(new EchoActivities());
        client = testEnv.getWorkflowClient();
        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void aLoopRunHistoryReplaysAndCarriesTheContainerOutputVersionMarker() throws Exception {
        WorkflowGraphDto loop = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start")),
                new WorkflowNodeDto("n2", "loop", Map.of(), Map.of("label", "Each")),
                new WorkflowNodeDto("n3", "code", Map.of(), Map.of("label", "Body"))),
            List.of(new WorkflowEdgeDto("e1", "n1", "n2", null, null), new WorkflowEdgeDto("e2", "n2", "n3", null, null)));
        WorkflowRunInput input = new WorkflowRunInput(
            "88888888-8888-8888-8888-888888888888", "def-r", "Replay", loop, null, "[\"a\",\"b\"]", "tester",
            "tenant-1", "user-1", Set.of(), 50, 30, 2, null, "draft");
        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).setWorkflowId("replay-1").build());
        assertThat(workflow.run(input).status()).isEqualTo("COMPLETED");

        var history = client.fetchHistory("replay-1");
        String changeId = java.util.Base64.getEncoder().encodeToString(
            "\"container-node-output-v1\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(history.toJson(true)).contains("EVENT_TYPE_MARKER_RECORDED").contains(changeId.replace("=", ""));
        WorkflowReplayer.replayWorkflowExecution(history, WorkflowRunWorkflowImpl.class);
    }

    private static class EchoActivities implements WorkflowNodeActivities {
        final List<NodeExecutionActivityInput> calls = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public NodeExecutionActivityResult executeNode(NodeExecutionActivityInput input) {
            calls.add(input);
            return new NodeExecutionActivityResult(
                "n1".equals(input.node().id()) ? input.input() : "out", "OK", "ok", null, null, List.of(), null, null, 1000L, 2000L);
        }

        @Override
        public void recordProgress(RunProgressInput input) {
            // not under test
        }

        @Override
        public void recordContainerOutput(ContainerOutputInput input) {
            // not under test
        }
    }
}
