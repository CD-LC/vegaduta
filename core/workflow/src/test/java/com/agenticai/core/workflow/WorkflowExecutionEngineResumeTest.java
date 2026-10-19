package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkflowExecutionEngine#resume}'s core contract (F-05 approval-node
 * resume): re-entry happens AT the approved node with the recorded input,
 * only that node and its downstream successors execute (completed
 * pre-approval nodes are never re-run - their contribution is the carried
 * input), the prior trace survives as history, and a resume pointed at a
 * node that no longer exists fails the run with an explained reason instead
 * of leaving it stuck RUNNING. {@link WorkflowNodeExecutor} is mocked here
 * on purpose - node ORDER and INPUTS are what this suite pins down; the
 * approval gate's grant handling is covered by
 * {@code WorkflowApprovalActionExecutorTest} and the node executor's own
 * gate logic.
 */
class WorkflowExecutionEngineResumeTest {

    private WorkflowRunRepository runRepository;
    private WorkflowNodeExecutor nodeExecutor;
    private WorkflowExecutionEngine engine;
    private final List<String> executedNodes = new ArrayList<>();

    /** Same JavaTimeModule necessity as {@code WorkflowExecutionEngineFailProofTest#mapper}. */
    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @BeforeEach
    void setUp() {
        runRepository = mock(WorkflowRunRepository.class);
        nodeExecutor = mock(WorkflowNodeExecutor.class);
        engine = new WorkflowExecutionEngine(
            runRepository, mock(OrchestratorService.class), nodeExecutor,
            mock(WorkflowEventPublisher.class), mapper(), mock(PlatformIssueService.class),
            mock(ObjectProvider.class),
            mock(ObjectProvider.class),
            mock(WorkflowRunCancellationService.class),
            mock(WorkflowRunPauseService.class),
            null, null,
            new com.agenticai.core.security.concurrent.ContextPropagatingExecutor("test-branch-", 16));
        executedNodes.clear();
        when(nodeExecutor.executeNode(anyString(), anyString(), anyString(), any(WorkflowNodeDto.class),
            any(), any(), any(), any())).thenAnswer(invocation -> {
                WorkflowNodeDto node = invocation.getArgument(3);
                String input = invocation.getArgument(4);
                executedNodes.add(node.id() + "<-" + input);
                return WorkflowNodeExecutor.NodeExecutionResult.ok("out-" + node.id(), "ran " + node.id(), null);
            });
    }

    private static WorkflowNodeDto node(String id, String type) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", Map.of(), "label", id));
    }

    /** trigger -> approval -> agent: the canonical F-05 halt-and-resume shape. */
    private static WorkflowGraphDto graph() {
        return new WorkflowGraphDto(
            List.of(node("t1", "trigger"), node("a1", "approval"), node("g1", "agent")),
            List.of(new WorkflowEdgeDto("e1", "t1", "a1", null, null),
                new WorkflowEdgeDto("e2", "a1", "g1", null, null)));
    }

    private static WorkflowDefinition definitionWithId() throws ReflectiveOperationException {
        WorkflowDefinition definition = new WorkflowDefinition(
            "Test Workflow", "desc", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE);
        java.lang.reflect.Field idField = WorkflowDefinition.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(definition, UUID.randomUUID());
        return definition;
    }

    private static WorkflowRun awaitingRun(WorkflowDefinition definition, String traceJson) {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), definition.getId(), definition.getName(), "user-1");
        run.complete(WorkflowRunStatus.AWAITING_APPROVAL, traceJson, "Awaiting human approval (request x).",
            null, null);
        return run;
    }

    private static List<NodeTraceEntry> priorTrace() {
        Instant then = Instant.now().minusSeconds(3600);
        return List.of(
            new NodeTraceEntry("t1", "trigger", "t1", "OK", "Received: original input", then, then, List.of()),
            new NodeTraceEntry("a1", "approval", "a1", "AWAITING_APPROVAL", "Awaiting human approval (request x).",
                then, then, List.of()));
    }

    @Test
    void resumeExecutesTheApprovedNodeAndItsSuccessorsOnlyNeverTheAlreadyCompletedOnes() throws Exception {
        WorkflowDefinition definition = definitionWithId();
        WorkflowRun run = awaitingRun(definition, "[]");

        WorkflowRun result = engine.resume(definition, graph(), run, priorTrace(), "a1", "carried-input", "user-1");

        assertEquals(WorkflowRunStatus.COMPLETED, result.getStatus());
        // The approved node runs first, fed the input recorded at halt time
        // (NOT re-derived by re-running the trigger); then only its
        // downstream successor - t1 never executes again.
        assertEquals(List.of("a1<-carried-input", "g1<-out-a1"), executedNodes);
    }

    @Test
    void resumeCarriesThePriorTraceForwardAndMarksTheResumePoint() throws Exception {
        WorkflowDefinition definition = definitionWithId();
        WorkflowRun run = awaitingRun(definition, "[]");

        WorkflowRun result = engine.resume(definition, graph(), run, priorTrace(), "a1", "in", "user-1");

        String trace = result.getTraceJson();
        assertTrue(trace.contains("Received: original input"),
            "prior trace entries must survive the resume, got: " + trace);
        assertTrue(trace.contains("AWAITING_APPROVAL"),
            "the halt entry stays in the timeline as history, got: " + trace);
        assertTrue(trace.contains("Approval granted - resuming run from this node."),
            "the resume point must be recorded (it doubles as the recovery job's heartbeat), got: " + trace);
    }

    @Test
    void resumePointedAtADeletedNodeFailsTheRunWithAClearReasonInsteadOfStrandingIt() throws Exception {
        WorkflowDefinition definition = definitionWithId();
        WorkflowRun run = awaitingRun(definition, "[]");

        WorkflowRun result = engine.resume(definition, graph(), run, priorTrace(), "ghost", "in", "user-1");

        // The caller already flipped the run to RUNNING - a validation
        // failure here must terminalize it, not leave it for the recovery
        // reaper to mislabel hours later.
        assertEquals(WorkflowRunStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("no longer exists"),
            "reason must say the node vanished, got: " + result.getErrorMessage());
        assertTrue(executedNodes.isEmpty(), "no node may execute when the resume point is gone");
    }

    @Test
    void freshExecuteStillStartsFromTheTriggerExactlyAsBeforeTheExtraction() throws Exception {
        // Regression guard on the runTraversal extraction: the pre-existing
        // execute() contract (trigger resolution, default input) must be
        // bit-identical - this pins the fresh-run path through the SAME
        // mocked executor the resume tests use, so a divergence between the
        // two entry points would show up here as a changed node order.
        WorkflowDefinition definition = definitionWithId();

        WorkflowRun result = engine.execute(definition, graph(), null, "user-1");

        assertEquals(WorkflowRunStatus.COMPLETED, result.getStatus());
        assertEquals(List.of("t1<-Workflow started manually.", "a1<-out-t1", "g1<-out-a1"), executedNodes);
    }
}
