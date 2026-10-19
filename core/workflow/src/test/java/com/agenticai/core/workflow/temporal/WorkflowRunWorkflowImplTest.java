package com.agenticai.core.workflow.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real {@link WorkflowRunWorkflowImpl} graph-traversal logic
 * against Temporal's in-memory {@link TestWorkflowEnvironment} (no live
 * Temporal server needed - see the {@code temporal-testing} test-scope
 * dependency this module's pom.xml added for Stage 3), with {@link
 * WorkflowNodeActivities} faked by {@link FakeWorkflowNodeActivities} so
 * this test asserts on orchestration decisions (which node runs next, when
 * a halt/failure propagates, what gets persisted) rather than depending on
 * real agent/tool/channel calls - matching this project's standing
 * test-first workflow (task #101) the same way {@code
 * ChatClientToolCallVerifierServiceTest} mocks {@code ChatClient}.
 *
 * <p>A hand-written fake, not a Mockito mock: Temporal's {@code
 * Worker.registerActivitiesImplementations} reflects on the concrete class
 * of whatever's registered, and Mockito's ByteBuddy-generated mock subclass
 * copies the interface's {@code @ActivityMethod} annotations onto its own
 * overriding methods - which Temporal's registration then rejects
 * ("annotation can be used only on the interface method it implements"),
 * even though the annotation is only ever declared once, on {@link
 * WorkflowNodeActivities} itself. A plain hand-written implementation
 * doesn't have this problem since it has no copied/duplicated annotations.
 */
class WorkflowRunWorkflowImplTest {

    private TestWorkflowEnvironment testEnv;
    private FakeWorkflowNodeActivities activities;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        activities = new FakeWorkflowNodeActivities();
        Worker worker = testEnv.newWorker(TemporalConfig.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(WorkflowRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        client = testEnv.getWorkflowClient();
        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void walksTriggerThenAgentNodeAndRecordsCompletion() {
        WorkflowNodeDto trigger = new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start"));
        WorkflowNodeDto agent = new WorkflowNodeDto(
            "n2", "agent", Map.of(), Map.of("label", "Agent", "config", Map.of("agentId", "irrelevant-for-this-test")));
        WorkflowEdgeDto edge = new WorkflowEdgeDto("e1", "n1", "n2", null, null);
        WorkflowGraphDto graph = new WorkflowGraphDto(List.of(trigger, agent), List.of(edge));

        activities.executeNodeAnswer = in -> new NodeExecutionActivityResult(
            "reply for " + in.node().id(), "OK", "ok", null, null, List.of(), null, null, 1000L, 2000L);

        WorkflowRunInput input = new WorkflowRunInput(
            "11111111-1111-1111-1111-111111111111", "def-1", "Test Workflow", graph, null, "hello", "tester",
            "tenant-1", "user-1", Set.of("ROLE_tenant-admin"), 50, 30, 2, null);

        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());

        WorkflowRunResult result = workflow.run(input);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.errorMessage()).isNull();
        // Both nodes actually ran (trigger, then agent) via the fake activity.
        assertThat(activities.executeNodeCalls).hasSize(2);
        assertThat(activities.executeNodeCalls.get(0).node().id()).isEqualTo("n1");
        assertThat(activities.executeNodeCalls.get(1).node().id()).isEqualTo("n2");
        // Exactly one terminal COMPLETED progress record at the end.
        assertThat(activities.recordProgressCalls)
            .anyMatch(p -> p.terminal() && "COMPLETED".equals(p.status()));
    }

    @Test
    void parksOnApprovalThenResumesWhenTheDecisionSignalArrives() {
        // The v1 signal-resume contract (await-approval-resume-v1 changeId):
        // a node halting AWAITING_APPROVAL no longer ends the execution -
        // the workflow writes the same terminal-shaped row as before and
        // then parks on Workflow.await until approvalDecision arrives. The
        // approved node re-executes with the replay grant serialized on the
        // activity input.
        WorkflowNodeDto trigger = new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start"));
        WorkflowNodeDto approval = new WorkflowNodeDto("n2", "approval", Map.of(), Map.of("label", "Needs approval"));
        WorkflowNodeDto after = new WorkflowNodeDto("n3", "agent", Map.of(), Map.of("label", "Continue"));
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger, approval, after),
            List.of(
                new WorkflowEdgeDto("e1", "n1", "n2", null, null),
                new WorkflowEdgeDto("e2", "n2", "n3", null, null)));

        activities.executeNodeAnswer = in -> {
            // The approval node halts on its FIRST execution only; the
            // resume call arrives carrying the replay grant (the real gate
            // lives in WorkflowNodeExecutor - here we just assert the grant
            // was serialized onto the input as designed).
            if ("n2".equals(in.node().id()) && in.approvalReplayActionType() == null) {
                return new NodeExecutionActivityResult(
                    null, "AWAITING_APPROVAL", "Awaiting human approval (request abc123).", null,
                    "AWAITING_APPROVAL", List.of(), null, null, 1000L, 1500L);
            }
            return new NodeExecutionActivityResult(
                "out-" + in.node().id(), "OK", "ok", null, null, List.of(), null, null, 500L, 900L);
        };

        WorkflowRunInput input = new WorkflowRunInput(
            "22222222-2222-2222-2222-222222222222", "def-2", "Approval Workflow", graph, null, "hello", "tester",
            "tenant-1", "user-1", Set.of(), 50, 30, 2, null);

        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());

        // Async start: run() now blocks parked on the await, so drive the
        // signal from the test thread like WorkflowApprovalActionExecutor
        // would. A signal sent "too early" is not lost - pendingApproval is
        // set before await evaluates its predicate.
        WorkflowClient.start(workflow::run, input);
        workflow.approvalDecision(new WorkflowApprovalSignal("n2", true, "admin-user", null));

        WorkflowRunResult result = io.temporal.client.WorkflowStub.fromTyped(workflow)
            .getResult(WorkflowRunResult.class);

        assertThat(result.status()).isEqualTo("COMPLETED");
        // n2 ran twice: once halted, once replayed with the grant.
        assertThat(activities.executeNodeCalls)
            .filteredOn(in -> "n2".equals(in.node().id()))
            .hasSize(2)
            .extracting(NodeExecutionActivityInput::approvalReplayActionType)
            .containsExactly(null, "workflow.node.approval");
        // The mid-run AWAITING_APPROVAL write still happened (the row shape
        // the approvals inbox reads), then the run completed.
        assertThat(activities.recordProgressCalls)
            .anyMatch(p -> p.terminal() && "AWAITING_APPROVAL".equals(p.status()));
        assertThat(activities.recordProgressCalls)
            .anyMatch(p -> p.terminal() && "COMPLETED".equals(p.status()));
    }

    @Test
    void parkedApprovalEndsFailedWhenTheSignalCarriesRejection() {
        WorkflowNodeDto trigger = new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start"));
        WorkflowNodeDto approval = new WorkflowNodeDto("n2", "approval", Map.of(), Map.of("label", "Needs approval"));
        WorkflowGraphDto graph = new WorkflowGraphDto(List.of(trigger, approval), List.of(
            new WorkflowEdgeDto("e1", "n1", "n2", null, null)));

        activities.executeNodeAnswer = in -> "n2".equals(in.node().id())
            ? new NodeExecutionActivityResult(
                null, "AWAITING_APPROVAL", "Awaiting human approval.", null,
                "AWAITING_APPROVAL", List.of(), null, null, 1000L, 1500L)
            : new NodeExecutionActivityResult(
                "out", "OK", "ok", null, null, List.of(), null, null, 500L, 900L);

        WorkflowRunInput input = new WorkflowRunInput(
            "22222222-2222-2222-2222-222222222223", "def-2", "Approval Workflow", graph, null, "hello", "tester",
            "tenant-1", "user-1", Set.of(), 50, 30, 2, null);

        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());

        WorkflowClient.start(workflow::run, input);
        workflow.approvalDecision(new WorkflowApprovalSignal("n2", false, "admin-user", "too risky"));

        WorkflowRunResult result = io.temporal.client.WorkflowStub.fromTyped(workflow)
            .getResult(WorkflowRunResult.class);

        // Rejection: the workflow returns FAILED without re-writing the row
        // (WorkflowApprovalActionExecutor's reject path already terminal-
        // wrote it - a second write would clobber the decider/reason).
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).contains("admin-user").contains("too risky");
        // n2 executed exactly once - never replayed.
        assertThat(activities.executeNodeCalls)
            .filteredOn(in -> "n2".equals(in.node().id()))
            .hasSize(1);
        assertThat(activities.recordProgressCalls)
            .filteredOn(p -> p.terminal() && "FAILED".equals(p.status()))
            .isEmpty();
    }

    @Test
    void parkedApprovalTimesOutFailedWhenNoDecisionEverArrives() {
        WorkflowNodeDto trigger = new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start"));
        WorkflowNodeDto approval = new WorkflowNodeDto("n2", "approval", Map.of(), Map.of("label", "Needs approval"));
        WorkflowGraphDto graph = new WorkflowGraphDto(List.of(trigger, approval), List.of(
            new WorkflowEdgeDto("e1", "n1", "n2", null, null)));

        activities.executeNodeAnswer = in -> "n2".equals(in.node().id())
            ? new NodeExecutionActivityResult(
                null, "AWAITING_APPROVAL", "Awaiting human approval.", null,
                "AWAITING_APPROVAL", List.of(), null, null, 1000L, 1500L)
            : new NodeExecutionActivityResult(
                "out", "OK", "ok", null, null, List.of(), null, null, 500L, 900L);

        WorkflowRunInput input = new WorkflowRunInput(
            "22222222-2222-2222-2222-222222222224", "def-2", "Approval Workflow", graph, null, "hello", "tester",
            "tenant-1", "user-1", Set.of(), 50, 30, 2, null);

        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());

        WorkflowClient.start(workflow::run, input);
        // TestWorkflowEnvironment virtual time: skip past the 7-day wait.
        testEnv.sleep(java.time.Duration.ofDays(8));

        WorkflowRunResult result = io.temporal.client.WorkflowStub.fromTyped(workflow)
            .getResult(WorkflowRunResult.class);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).contains("expired");
        assertThat(activities.recordProgressCalls)
            .anyMatch(p -> p.terminal() && "FAILED".equals(p.status()));
    }

    @Test
    void fansOutParallelNodeBranchesConcurrentlyThenJoinsOnTheLastBranch() {
        WorkflowNodeDto trigger = new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start"));
        WorkflowNodeDto parallel = new WorkflowNodeDto("n2", "parallel", Map.of(), Map.of("label", "Fan out"));
        WorkflowNodeDto branchA = new WorkflowNodeDto("n3", "agent", Map.of(), Map.of("label", "Branch A"));
        WorkflowNodeDto branchB = new WorkflowNodeDto("n4", "agent", Map.of(), Map.of("label", "Branch B"));
        WorkflowNodeDto join = new WorkflowNodeDto("n5", "agent", Map.of(), Map.of("label", "Join"));
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger, parallel, branchA, branchB, join),
            List.of(
                new WorkflowEdgeDto("e1", "n1", "n2", null, null),
                new WorkflowEdgeDto("e2", "n2", "n3", null, null),
                new WorkflowEdgeDto("e3", "n2", "n4", null, null),
                new WorkflowEdgeDto("e4", "n3", "n5", null, null),
                new WorkflowEdgeDto("e5", "n4", "n5", null, null)));

        activities.executeNodeAnswer = in -> new NodeExecutionActivityResult(
            "output-for-" + in.node().id(), "OK", "ok", null, null, List.of(), null, null, 1000L, 2000L);

        WorkflowRunInput input = new WorkflowRunInput(
            "33333333-3333-3333-3333-333333333333", "def-3", "Parallel Workflow", graph, null, "hello", "tester",
            "tenant-1", "user-1", Set.of(), 50, 30, 2, null);

        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());

        WorkflowRunResult result = workflow.run(input);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.errorMessage()).isNull();
        // Trigger, both parallel branches, and the join node downstream of
        // them all actually ran via the fake activity - real concurrent
        // fan-out via Async.function/Promise.allOf, not the silent
        // "only the first branch's chain ran" bug the old IllegalStateException
        // guard replaced.
        assertThat(activities.executeNodeCalls)
            .extracting(in -> in.node().id())
            .containsExactlyInAnyOrder("n1", "n3", "n4", "n5");
        // Terminal COMPLETED record's trace includes a labelled entry per
        // branch (parallel i/N) plus the Parallel node's own "fanned out"
        // summary entry - mirrors WorkflowExecutionEngine's identical trace
        // shape for the same feature.
        assertThat(activities.recordProgressCalls)
            .filteredOn(p -> p.terminal() && "COMPLETED".equals(p.status()))
            .hasSize(1)
            .flatExtracting(RunProgressInput::trace)
            .extracting(t -> t.label())
            .anyMatch(label -> label.contains("Branch A") && label.contains("(parallel"))
            .anyMatch(label -> label.contains("Branch B") && label.contains("(parallel"))
            .contains("Fan out");
    }

    @Test
    void haltsTheWholeRunWhenAParallelBranchRequestsHalt() {
        WorkflowNodeDto trigger = new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start"));
        WorkflowNodeDto parallel = new WorkflowNodeDto("n2", "parallel", Map.of(), Map.of("label", "Fan out"));
        WorkflowNodeDto branchA = new WorkflowNodeDto("n3", "agent", Map.of(), Map.of("label", "Branch A"));
        WorkflowNodeDto branchB = new WorkflowNodeDto("n4", "approval", Map.of(), Map.of("label", "Branch B needs approval"));
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger, parallel, branchA, branchB),
            List.of(
                new WorkflowEdgeDto("e1", "n1", "n2", null, null),
                new WorkflowEdgeDto("e2", "n2", "n3", null, null),
                new WorkflowEdgeDto("e3", "n2", "n4", null, null)));

        activities.executeNodeAnswer = in -> {
            if ("n4".equals(in.node().id())) {
                return new NodeExecutionActivityResult(
                    null, "AWAITING_APPROVAL", "Branch B awaiting human approval.", null,
                    "AWAITING_APPROVAL", List.of(), null, null, 1000L, 1500L);
            }
            return new NodeExecutionActivityResult(
                "output-for-" + in.node().id(), "OK", "ok", null, null, List.of(), null, null, 1000L, 2000L);
        };

        WorkflowRunInput input = new WorkflowRunInput(
            "44444444-4444-4444-4444-444444444444", "def-4", "Parallel Halt Workflow", graph, null, "hello", "tester",
            "tenant-1", "user-1", Set.of(), 50, 30, 2, null);

        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());

        WorkflowRunResult result = workflow.run(input);

        // One branch's halt signal halts the whole run - matches
        // WorkflowExecutionEngine's identical "any branch halts, the
        // Parallel node halts" semantics (see that method's parallel
        // branch), even though both branch Activities still ran to
        // completion (Temporal doesn't cancel sibling branches just because
        // one signals a halt via its own NodeExecutionActivityResult - only
        // an actual thrown/unretryable Activity failure would abort the
        // others via Promise.allOf's fail-fast behavior).
        assertThat(result.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(activities.executeNodeCalls)
            .extracting(in -> in.node().id())
            .containsExactlyInAnyOrder("n1", "n3", "n4");
        assertThat(activities.recordProgressCalls)
            .anyMatch(p -> p.terminal() && "AWAITING_APPROVAL".equals(p.status()));
    }

    @Test
    void fanOutNodeRunsItsSingleWorkerOncePerRuntimeItemThenContinuesPastIt() {
        // Dynamic Fan-Out: unlike the Parallel tests above (whose branch set
        // is the node's own outgoing edges, fixed in the graph), the fan-out
        // width here comes from RUNTIME data - the trigger's output resolves
        // to a 3-item JSON array, so the single worker node must run exactly
        // 3 times even though the graph only draws it once.
        WorkflowNodeDto trigger = new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start"));
        WorkflowNodeDto fanOut = new WorkflowNodeDto("n2", "fan_out", Map.of(),
            Map.of("label", "Per lead", "config", Map.of("maxConcurrency", 2)));
        WorkflowNodeDto worker = new WorkflowNodeDto("n3", "agent", Map.of(), Map.of("label", "Score lead"));
        WorkflowNodeDto after = new WorkflowNodeDto("n4", "agent", Map.of(), Map.of("label", "Summarize"));
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger, fanOut, worker, after),
            List.of(
                new WorkflowEdgeDto("e1", "n1", "n2", null, null),
                new WorkflowEdgeDto("e2", "n2", "n3", null, null),
                new WorkflowEdgeDto("e3", "n3", "n4", null, null)));

        activities.executeNodeAnswer = in -> {
            if ("n1".equals(in.node().id())) {
                return new NodeExecutionActivityResult(
                    "[\"a\",\"b\",\"c\"]", "OK", "ok", null, null, List.of(), null, null, 1000L, 2000L);
            }
            return new NodeExecutionActivityResult(
                "scored-" + in.input(), "OK", "ok", null, null, List.of(), null, null, 1000L, 2000L);
        };

        WorkflowRunInput input = new WorkflowRunInput(
            "55555555-5555-5555-5555-555555555555", "def-5", "Fan-out Workflow", graph, null, "hello", "tester",
            "tenant-1", "user-1", Set.of(), 50, 30, 2, null);

        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());

        WorkflowRunResult result = workflow.run(input);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.errorMessage()).isNull();
        // Trigger once, worker once per item, downstream join node once.
        assertThat(activities.executeNodeCalls)
            .extracting(in -> in.node().id())
            .containsExactly("n1", "n3", "n3", "n3", "n4");
        // Every runtime item was fed to the worker exactly once - in ANY
        // order, deliberately: the two items sharing a concurrency slice
        // genuinely race on the activity worker's executor, so asserting
        // dispatch order here would be asserting a coincidence. What IS
        // order-guaranteed (and asserted just below) is the merged output the
        // downstream node receives: item order, never completion order.
        assertThat(activities.executeNodeCalls)
            .filteredOn(in -> "n3".equals(in.node().id()))
            .extracting(NodeExecutionActivityInput::input)
            .containsExactlyInAnyOrder("a", "b", "c");
        assertThat(activities.executeNodeCalls)
            .filteredOn(in -> "n4".equals(in.node().id()))
            .extracting(NodeExecutionActivityInput::input)
            .containsExactly("[\"scored-a\",\"scored-b\",\"scored-c\"]");
        // Terminal trace carries one labelled entry per item plus the
        // fan-out node's own summary entry, mirroring Loop's/Parallel's
        // trace shape for their own constructs.
        assertThat(activities.recordProgressCalls)
            .filteredOn(p -> p.terminal() && "COMPLETED".equals(p.status()))
            .hasSize(1)
            .flatExtracting(RunProgressInput::trace)
            .extracting(t -> t.label())
            .anyMatch(label -> label.contains("Score lead") && label.contains("(fan-out 1/3)"))
            .anyMatch(label -> label.contains("Score lead") && label.contains("(fan-out 3/3)"))
            .contains("Per lead");
    }

    @Test
    void fanOutWithNonArrayInputRunsZeroItemsAndContinuesPastTheWorker() {
        // Mirrors Loop's documented zero-iteration behavior: input that
        // doesn't resolve to a JSON array fans out to nothing, skips the
        // worker entirely, and the run continues past it with "[]" - visible
        // in the fan-out node's own trace summary, never a run-ending error.
        WorkflowNodeDto trigger = new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start"));
        WorkflowNodeDto fanOut = new WorkflowNodeDto("n2", "fan_out", Map.of(), Map.of("label", "Per row"));
        WorkflowNodeDto worker = new WorkflowNodeDto("n3", "agent", Map.of(), Map.of("label", "Worker"));
        WorkflowNodeDto after = new WorkflowNodeDto("n4", "agent", Map.of(), Map.of("label", "After"));
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(trigger, fanOut, worker, after),
            List.of(
                new WorkflowEdgeDto("e1", "n1", "n2", null, null),
                new WorkflowEdgeDto("e2", "n2", "n3", null, null),
                new WorkflowEdgeDto("e3", "n3", "n4", null, null)));

        activities.executeNodeAnswer = in -> new NodeExecutionActivityResult(
            "plain text, not an array", "OK", "ok", null, null, List.of(), null, null, 1000L, 2000L);

        WorkflowRunInput input = new WorkflowRunInput(
            "66666666-6666-6666-6666-666666666666", "def-6", "Empty Fan-out Workflow", graph, null, "hello", "tester",
            "tenant-1", "user-1", Set.of(), 50, 30, 2, null);

        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());

        WorkflowRunResult result = workflow.run(input);

        assertThat(result.status()).isEqualTo("COMPLETED");
        // The worker itself never ran - only the trigger and the node past
        // the fan-in, which received the empty-array merge.
        assertThat(activities.executeNodeCalls)
            .extracting(in -> in.node().id())
            .containsExactly("n1", "n4");
        assertThat(activities.executeNodeCalls)
            .filteredOn(in -> "n4".equals(in.node().id()))
            .extracting(NodeExecutionActivityInput::input)
            .containsExactly("[]");
    }

    // ---- draft mode (pins): the mode rides the workflow input and lands on every node activity

    private WorkflowRunResult runGraph(WorkflowGraphDto graph, String text, String runMode, String runId) {
        WorkflowRunInput input = new WorkflowRunInput(
            runId, "def-m", "Mode Workflow", graph, null, text, "tester", "tenant-1", "user-1", Set.of(),
            50, 30, 2, null, runMode);
        WorkflowRunWorkflow workflow = client.newWorkflowStub(
            WorkflowRunWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(TemporalConfig.TASK_QUEUE).build());
        return workflow.run(input);
    }

    private void echoTriggerThenUpperCase() {
        activities.executeNodeAnswer = in -> new NodeExecutionActivityResult(
            "n1".equals(in.node().id()) ? in.input() : "out-" + in.node().id(), "OK", "ok", null, null, List.of(),
            null, null, 1000L, 2000L);
    }

    @Test
    void aDraftRunStampsItsModeOnEveryNodeActivity() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start")),
                new WorkflowNodeDto("n2", "code", Map.of(), Map.of("label", "Step"))),
            List.of(new WorkflowEdgeDto("e1", "n1", "n2", null, null)));
        echoTriggerThenUpperCase();

        WorkflowRunResult result = runGraph(graph, "hello", "draft", "33333333-3333-3333-3333-333333333333");

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(activities.executeNodeCalls).hasSize(2);
        assertThat(activities.executeNodeCalls).allSatisfy(call -> {
            assertThat(call.expressionScope()).isNotNull();
            assertThat(call.expressionScope().mode()).isEqualTo("draft");
        });
    }

    @Test
    void aProductionRunBindsNoScopeAtAllSoItCanNeverServeAPin() {
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start")),
                new WorkflowNodeDto("n2", "code", Map.of(), Map.of("label", "Step"))),
            List.of(new WorkflowEdgeDto("e1", "n1", "n2", null, null)));
        echoTriggerThenUpperCase();

        // No mode (an input serialised before the field existed), and an explicit "production".
        runGraph(graph, "hello", null, "44444444-4444-4444-4444-444444444444");
        runGraph(graph, "hello", "production", "55555555-5555-5555-5555-555555555555");

        assertThat(activities.executeNodeCalls).hasSize(4);
        assertThat(activities.executeNodeCalls).allSatisfy(call -> assertThat(call.expressionScope()).isNull());
    }

    @Test
    void theLegacyFourteenArgumentInputIsAProductionRun() {
        WorkflowRunInput legacy = new WorkflowRunInput(
            "r", "d", "n", new WorkflowGraphDto(List.of(), List.of()), null, "x", "t", "tn", "u", Set.of(), 50, 30, 2, null);
        assertThat(legacy.runMode()).isNull();
    }

    @Test
    void loopBodiesAndParallelBranchesCarryTheDraftModeToo() {
        WorkflowGraphDto loop = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start")),
                new WorkflowNodeDto("n2", "loop", Map.of(), Map.of("label", "Each")),
                new WorkflowNodeDto("n3", "code", Map.of(), Map.of("label", "Body"))),
            List.of(new WorkflowEdgeDto("e1", "n1", "n2", null, null), new WorkflowEdgeDto("e2", "n2", "n3", null, null)));
        echoTriggerThenUpperCase();
        runGraph(loop, "[\"a\",\"b\"]", "draft", "66666666-6666-6666-6666-666666666666");

        List<NodeExecutionActivityInput> bodies = activities.executeNodeCalls.stream()
            .filter(c -> "n3".equals(c.node().id())).toList();
        assertThat(bodies).hasSize(2);
        assertThat(bodies).allSatisfy(c -> assertThat(c.expressionScope().mode()).isEqualTo("draft"));
        assertThat(bodies.get(1).expressionScope().index()).isEqualTo(1);
        assertThat(bodies.get(1).expressionScope().item()).isEqualTo("b");

        activities.executeNodeCalls.clear();
        WorkflowGraphDto parallel = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start")),
                new WorkflowNodeDto("n2", "parallel", Map.of(), Map.of("label", "Fan")),
                new WorkflowNodeDto("b1", "code", Map.of(), Map.of("label", "B1")),
                new WorkflowNodeDto("b2", "code", Map.of(), Map.of("label", "B2"))),
            List.of(new WorkflowEdgeDto("e1", "n1", "n2", null, null), new WorkflowEdgeDto("e2", "n2", "b1", null, null),
                new WorkflowEdgeDto("e3", "n2", "b2", null, null)));
        runGraph(parallel, "x", "draft", "77777777-7777-7777-7777-777777777777");
        assertThat(activities.executeNodeCalls.stream().filter(c -> c.node().id().startsWith("b")))
            .hasSize(2)
            .allSatisfy(c -> assertThat(c.expressionScope().mode()).isEqualTo("draft"));
    }

    @Test
    void loopParallelAndFanOutRecordTheirOwnMergedOutputThroughAnActivity() {
        echoTriggerThenUpperCase();
        WorkflowGraphDto loop = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start")),
                new WorkflowNodeDto("n2", "loop", Map.of(), Map.of("label", "Each")),
                new WorkflowNodeDto("n3", "code", Map.of(), Map.of("label", "Body"))),
            List.of(new WorkflowEdgeDto("e1", "n1", "n2", null, null), new WorkflowEdgeDto("e2", "n2", "n3", null, null)));
        runGraph(loop, "[\"a\",\"b\"]", "draft", "88888888-8888-8888-8888-888888888881");

        WorkflowGraphDto parallel = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start")),
                new WorkflowNodeDto("p1", "parallel", Map.of(), Map.of("label", "Fan")),
                new WorkflowNodeDto("b1", "code", Map.of(), Map.of("label", "B1")),
                new WorkflowNodeDto("b2", "code", Map.of(), Map.of("label", "B2"))),
            List.of(new WorkflowEdgeDto("e1", "n1", "p1", null, null), new WorkflowEdgeDto("e2", "p1", "b1", null, null),
                new WorkflowEdgeDto("e3", "p1", "b2", null, null)));
        runGraph(parallel, "x", null, "88888888-8888-8888-8888-888888888882");

        WorkflowGraphDto fanOut = new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("n1", "trigger", Map.of(), Map.of("label", "Start")),
                new WorkflowNodeDto("f1", "fan_out", Map.of(), Map.of("label", "Per row")),
                new WorkflowNodeDto("w1", "code", Map.of(), Map.of("label", "Worker"))),
            List.of(new WorkflowEdgeDto("e1", "n1", "f1", null, null), new WorkflowEdgeDto("e2", "f1", "w1", null, null)));
        runGraph(fanOut, "[\"a\",\"b\",\"c\"]", "production", "88888888-8888-8888-8888-888888888883");

        // One call per container, keyed by the container node (not its body), carrying the merged array that
        // the next node also receives, and the run id so the store row lands on the right run.
        assertThat(activities.containerOutputCalls).hasSize(3);
        ContainerOutputInput loopCall = activities.containerOutputCalls.stream()
            .filter(c -> "n2".equals(c.node().id())).findFirst().orElseThrow();
        assertThat(loopCall.runId()).isEqualTo("88888888-8888-8888-8888-888888888881");
        assertThat(loopCall.node().label()).isEqualTo("Each");
        assertThat(loopCall.output()).isEqualTo("[\"out-n3\",\"out-n3\"]");
        ContainerOutputInput parallelCall = activities.containerOutputCalls.stream()
            .filter(c -> "p1".equals(c.node().id())).findFirst().orElseThrow();
        assertThat(parallelCall.output()).contains("out-b1").contains("out-b2");
        ContainerOutputInput fanOutCall = activities.containerOutputCalls.stream()
            .filter(c -> "f1".equals(c.node().id())).findFirst().orElseThrow();
        assertThat(fanOutCall.output()).isEqualTo("[\"out-w1\",\"out-w1\",\"out-w1\"]");
        assertThat(activities.containerOutputCalls)
            .allSatisfy(c -> assertThat(c.tenantId()).isEqualTo("tenant-1"));
    }

    /**
     * Minimal hand-written test double - see this class's javadoc above for
     * why a Mockito mock can't be registered directly with a Temporal
     * {@code Worker}. {@code executeNodeAnswer} lets each test supply its
     * own per-node behavior; every call is recorded for assertions.
     */
    private static class FakeWorkflowNodeActivities implements WorkflowNodeActivities {
        Function<NodeExecutionActivityInput, NodeExecutionActivityResult> executeNodeAnswer;
        // synchronizedList: activity methods run on the test environment's own
        // activity worker threads, not the test thread - plain ArrayList would
        // risk a visibility/race issue between those writes and this test's
        // assertions after workflow.run() returns.
        final List<NodeExecutionActivityInput> executeNodeCalls = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<RunProgressInput> recordProgressCalls = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public NodeExecutionActivityResult executeNode(NodeExecutionActivityInput input) {
            executeNodeCalls.add(input);
            return executeNodeAnswer.apply(input);
        }

        @Override
        public void recordProgress(RunProgressInput input) {
            recordProgressCalls.add(input);
        }

        final List<ContainerOutputInput> containerOutputCalls = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void recordContainerOutput(ContainerOutputInput input) {
            containerOutputCalls.add(input);
        }
    }
}
