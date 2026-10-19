package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.safeLength;
import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;
import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** "workflow": runs another saved workflow synchronously (workflow-triggers-workflow, #846). */
@Component
class WorkflowTriggerNodeHandler implements NodeHandler {

    /**
     * Runtime cycle guard for "workflow" nodes (workflow-triggers-workflow, #846) - a thread-local
     * call stack of workflow definition ids currently executing on THIS thread. Correct and
     * complete for the default, synchronous {@link WorkflowExecutionEngine} path: a nested {@code
     * WorkflowService#run} call for a "workflow" node stays on the exact same request thread as its
     * caller, all the way down, so this genuinely sees the whole chain. {@link
     * OrchestratorService#validate} already rejects the single most common cycle (a node targeting
     * its own containing workflow) at save/activate time - this is the runtime backstop for the
     * harder case validation can't see: an indirect cycle across separate workflow definitions (A
     * triggers B triggers A). {@link #MAX_WORKFLOW_CALL_DEPTH} is the honest fallback for the one
     * path this thread-local doesn't fully cover - the Temporal execution path, where each Activity
     * invocation can run on a different worker thread, so a nested call may not share this
     * thread-local with its caller. Every "workflow" node execution still goes through the depth
     * cap either way, so a chain can never grow unbounded even there.
     */
    private static final ThreadLocal<Deque<String>> WORKFLOW_CALL_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static final int MAX_WORKFLOW_CALL_DEPTH = 5;

    // ObjectProvider, not a plain WorkflowService: WorkflowService -> WorkflowExecutionEngine ->
    // WorkflowNodeExecutor -> (this handler) -> WorkflowService would be a circular bean
    // dependency at startup. Resolved lazily, when a "workflow" node actually runs.
    private final ObjectProvider<WorkflowService> workflowService;
    WorkflowTriggerNodeHandler(ObjectProvider<WorkflowService> workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("workflow");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeWorkflowTriggerNode(inv.node(), inv.input(), inv.workflowDefinitionId());
    }

    /**
     * "Trigger Workflow" node (#846, workflow-triggers-workflow) - runs a different, already-saved
     * workflow definition synchronously via {@link WorkflowService#run}, the exact same call the
     * canvas's own "Run" button makes, and uses that sub-run's last-step summary as this node's
     * output. Deliberately does NOT halt the parent run just because the sub-workflow failed or
     * paused for approval - unlike this node's OWN {@code requiresApproval} gate (which genuinely
     * does halt via {@link NodeExecutionResult#halted}), a sub-workflow's own internal outcome is
     * reported back as a SKIPPED result with the reason in its log, and the parent graph is free to
     * branch on that however its author wants (e.g. a downstream Condition node). See {@link
     * #WORKFLOW_CALL_STACK}'s javadoc for the two-layer recursion guard this relies on.
     */
    private NodeExecutionResult executeWorkflowTriggerNode(
            WorkflowNodeDto node, String input, String callingWorkflowId) {
        String targetWorkflowIdRaw = stringConfig(node, "targetWorkflowId", null);
        if (targetWorkflowIdRaw == null) {
            return NodeExecutionResult.skipped(
                    input, "No target workflow selected for this Workflow node.");
        }
        UUID targetWorkflowId;
        try {
            targetWorkflowId = UUID.fromString(targetWorkflowIdRaw);
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.skipped(
                    input, "Invalid target workflow id: " + targetWorkflowIdRaw);
        }
        if (targetWorkflowIdRaw.equalsIgnoreCase(callingWorkflowId)) {
            return NodeExecutionResult.skipped(
                    input,
                    "This node targets its own containing workflow - refusing to "
                            + "trigger it (would recurse forever). Point it at a different workflow.");
        }

        WorkflowService service = workflowService.getIfAvailable();
        if (service == null) {
            return NodeExecutionResult.skipped(
                    input, "Workflow triggering is unavailable right now (service not ready).");
        }

        Deque<String> callStack = WORKFLOW_CALL_STACK.get();
        if (callStack.contains(targetWorkflowIdRaw)) {
            return NodeExecutionResult.skipped(
                    input,
                    "Refusing to trigger workflow "
                            + targetWorkflowIdRaw
                            + " - it is already running earlier in this call chain, which would recurse indefinitely.");
        }
        if (callStack.size() >= MAX_WORKFLOW_CALL_DEPTH) {
            return NodeExecutionResult.skipped(
                    input,
                    "Refusing to trigger workflow "
                            + targetWorkflowIdRaw
                            + " - this workflow-triggers-workflow chain is already "
                            + callStack.size()
                            + " level(s) deep (max "
                            + MAX_WORKFLOW_CALL_DEPTH
                            + ").");
        }

        List<String> logLines = new ArrayList<>();
        logLines.add(
                "Triggering workflow "
                        + targetWorkflowIdRaw
                        + " with input ("
                        + safeLength(input)
                        + " chars): "
                        + truncate(input, 200));
        callStack.push(callingWorkflowId);
        try {
            WorkflowRunResponse subRun =
                    service.run(targetWorkflowId, input, "workflow:" + callingWorkflowId);
            String lastSummary =
                    subRun.trace().isEmpty()
                            ? null
                            : subRun.trace().get(subRun.trace().size() - 1).summary();
            String output = lastSummary != null ? lastSummary : (input == null ? "" : input);
            logLines.add(
                    "Sub-workflow '"
                            + subRun.workflowName()
                            + "' run "
                            + subRun.id()
                            + " finished: "
                            + subRun.status()
                            + (subRun.errorMessage() != null
                                    ? " (" + subRun.errorMessage() + ")"
                                    : "")
                            + ".");
            String summary = "Triggered '" + subRun.workflowName() + "' - " + subRun.status() + ".";
            if (subRun.status() == WorkflowRunStatus.COMPLETED) {
                return NodeExecutionResult.ok(output, summary, null, logLines);
            }
            logLines.add(
                    "Not halting this run - the parent workflow continues regardless of the sub-workflow's outcome.");
            return NodeExecutionResult.skipped(output, summary, logLines);
        } catch (RuntimeException e) {
            logLines.add(
                    "Failed to trigger workflow " + targetWorkflowIdRaw + ": " + e.getMessage());
            return NodeExecutionResult.skipped(
                    input, "Failed to trigger workflow: " + e.getMessage(), logLines);
        } finally {
            callStack.pop();
            if (callStack.isEmpty()) {
                // Avoid leaking this thread-local's backing Deque forever on
                // a pooled request-handling thread once the outermost
                // "workflow" node call on it has fully unwound.
                WORKFLOW_CALL_STACK.remove();
            }
        }
    }
}
