package com.agenticai.core.workflow.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Determinism guard for the workflow-side step classes. The fixtures under
 * {@code src/test/resources/temporal-histories} are event histories recorded by the workflow code as it was
 * BEFORE the traversal was split into {@code LoopStep}/{@code ParallelStep}/{@code FanOutStep}; replaying
 * them on the current code fails with a non-determinism error if a split changed a single command (an activity
 * call, a timer, a version marker, a signal wait) or its order. Regenerate only for a deliberate, versioned
 * change to the workflow, with {@code -Dworkflow.history.record=true}.
 */
class WorkflowRunWorkflowHistoryFixturesTest {

    private static final Path DIR = Path.of("src/test/resources/temporal-histories");
    private static final boolean RECORD = Boolean.getBoolean("workflow.history.record");

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TemporalConfig.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(WorkflowRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(new Activities());
        client = testEnv.getWorkflowClient();
        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void sequentialRunReplays() throws Exception {
        WorkflowGraphDto graph = graph(
            List.of(n("n1", "trigger"), n("n2", "agent"), n("n3", "agent")),
            List.of(e("n1", "n2"), e("n2", "n3")));
        runAndCheck("sequential", graph, "hello", "COMPLETED");
    }

    @Test
    void loopRunReplays() throws Exception {
        WorkflowGraphDto graph = graph(
            List.of(n("n1", "trigger"), n("n2", "loop"), n("n3", "agent"), n("n4", "agent")),
            List.of(e("n1", "n2"), e("n2", "n3"), e("n3", "n4")));
        runAndCheck("loop", graph, "[\"a\",\"b\",\"c\"]", "COMPLETED");
    }

    @Test
    void parallelRunReplays() throws Exception {
        WorkflowGraphDto graph = graph(
            List.of(n("n1", "trigger"), n("n2", "parallel"), n("b1", "agent"), n("b2", "agent"), n("n5", "agent")),
            List.of(e("n1", "n2"), e("n2", "b1"), e("n2", "b2"), e("b1", "n5"), e("b2", "n5")));
        runAndCheck("parallel", graph, "go", "COMPLETED");
    }

    @Test
    void fanOutRunReplaysAcrossConcurrencySlices() throws Exception {
        WorkflowNodeDto fanOut = new WorkflowNodeDto("n2", "fan_out", Map.of(),
            Map.of("label", "n2", "config", Map.of("maxConcurrency", 2)));
        WorkflowGraphDto graph = graph(
            List.of(n("n1", "trigger"), fanOut, n("n3", "agent"), n("n4", "agent")),
            List.of(e("n1", "n2"), e("n2", "n3"), e("n3", "n4")));
        runAndCheck("fan-out", graph, "[\"a\",\"b\",\"c\",\"d\",\"e\"]", "COMPLETED");
    }

    @Test
    void haltedRunReplays() throws Exception {
        WorkflowGraphDto graph = graph(
            List.of(n("n1", "trigger"), n("n2", "agent"), n("n3", "agent")),
            List.of(e("n1", "n2"), e("n2", "n3")));
        runAndCheck("halted", graph, "FAIL", "FAILED");
    }

    @Test
    void approvalParkAndSignalRunReplays() throws Exception {
        WorkflowGraphDto graph = graph(
            List.of(n("n1", "trigger"), n("n2", "approval"), n("n3", "agent")),
            List.of(e("n1", "n2"), e("n2", "n3")));
        WorkflowRunWorkflow workflow = stub("approval");
        WorkflowClient.start(workflow::run, input(graph, "hi"));
        workflow.approvalDecision(new WorkflowApprovalSignal("n2", true, "admin", null));
        WorkflowRunResult result = WorkflowStub.fromTyped(workflow).getResult(WorkflowRunResult.class);
        assertThat(result.status()).isEqualTo("COMPLETED");
        replayOrRecord("approval");
    }

    private void runAndCheck(String name, WorkflowGraphDto graph, String payload, String expectedStatus)
        throws Exception {
        WorkflowRunResult result = stub(name).run(input(graph, payload));
        assertThat(result.status()).isEqualTo(expectedStatus);
        replayOrRecord(name);
    }

    private void replayOrRecord(String name) throws Exception {
        var history = client.fetchHistory("fixture-" + name);
        Path file = DIR.resolve(name + ".json");
        if (RECORD) {
            Files.createDirectories(DIR);
            Files.writeString(file, history.toJson(true), StandardCharsets.UTF_8);
            return;
        }
        assertThat(file).as("recorded history fixture %s", file).exists();
        WorkflowReplayer.replayWorkflowExecution(Files.readString(file, StandardCharsets.UTF_8), WorkflowRunWorkflowImpl.class);
        // The same shape, freshly recorded on the current code, also replays on it.
        WorkflowReplayer.replayWorkflowExecution(history, WorkflowRunWorkflowImpl.class);
    }

    private WorkflowRunWorkflow stub(String name) {
        return client.newWorkflowStub(WorkflowRunWorkflow.class, WorkflowOptions.newBuilder()
            .setTaskQueue(TemporalConfig.TASK_QUEUE).setWorkflowId("fixture-" + name).build());
    }

    private static WorkflowRunInput input(WorkflowGraphDto graph, String payload) {
        return new WorkflowRunInput(
            "99999999-9999-9999-9999-999999999999", "def-fixture", "Fixture", graph, null, payload, "tester",
            "tenant-1", "user-1", Set.of(), 50, 30, 2, null, "draft");
    }

    private static WorkflowGraphDto graph(List<WorkflowNodeDto> nodes, List<WorkflowEdgeDto> edges) {
        return new WorkflowGraphDto(nodes, edges);
    }

    private static WorkflowNodeDto n(String id, String type) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", id));
    }

    private static WorkflowEdgeDto e(String from, String to) {
        return new WorkflowEdgeDto(from + "-" + to, from, to, null, null);
    }

    private static class Activities implements WorkflowNodeActivities {
        @Override
        public NodeExecutionActivityResult executeNode(NodeExecutionActivityInput in) {
            String id = in.node().id();
            if ("n1".equals(id)) {
                return new NodeExecutionActivityResult(in.input(), "OK", "ok", null, null, List.of(), null, null, 1000L, 2000L);
            }
            if ("n2".equals(id) && "approval".equals(in.node().type()) && in.approvalReplayActionType() == null) {
                return new NodeExecutionActivityResult(null, "AWAITING_APPROVAL", "Awaiting human approval (request abc).",
                    null, "AWAITING_APPROVAL", List.of(), null, null, 1000L, 1500L);
            }
            if ("n2".equals(id) && "FAIL".equals(in.input())) {
                return new NodeExecutionActivityResult(in.input(), "FAILED", "no", null, "FAILED", List.of(), null, null, 1000L, 1500L);
            }
            return new NodeExecutionActivityResult(
                "out-" + id,
                "OK", "ok", null, null, List.of("log"), null, null, 1000L, 2000L);
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
