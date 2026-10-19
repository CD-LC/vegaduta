package com.agenticai.core.workflow;


import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.approval.ApprovalReplayGrant;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** "human_input": halts the run for a structured form submission, then resumes under a replay grant. */
@Component
class HumanInputNodeHandler implements NodeHandler {

    private final ObjectProvider<com.agenticai.core.workflow.humaninput.WorkflowInputRequestService>
            inputRequestService;
    HumanInputNodeHandler(
            ObjectProvider<com.agenticai.core.workflow.humaninput.WorkflowInputRequestService>
                    inputRequestService) {
        this.inputRequestService = inputRequestService;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("human_input");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeHumanInputNode(
                inv.workflowDefinitionId(),
                inv.workflowDefinitionName(),
                inv.runId(),
                inv.node(),
                inv.input(),
                inv.requestedBy());
    }

    /**
     * Human Input node (Tier-3) - the structured-data counterpart of the Approval node's
     * approve/reject halt. First execution records a PENDING {@code workflow_input_request} (form
     * schema + upstream-input resume envelope snapshotted onto the row) and halts the run
     * AWAITING_INPUT. {@code POST /api/workflows/input-requests/{id}/submit} then re-enters the
     * engine at this node under an {@link ApprovalReplayGrant} for {@code
     * "workflow.node.human_input"} - the same single-use, same-thread mechanism approval-resume
     * uses - so THIS pass returns the submitted-values envelope as the node output instead of
     * re-halting. Config: {@code message} (shown to the human in the console), {@code fields}
     * ({@code [{key, label, type, required, options?}]}).
     */
    private NodeExecutionResult executeHumanInputNode(
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            WorkflowNodeDto node,
            String input,
            String requestedBy) {
        if (ApprovalReplayGrant.consume(
                com.agenticai.core.workflow.humaninput.WorkflowInputRequestService
                        .REPLAY_ACTION_TYPE)) {
            return NodeExecutionResult.ok(input, "Human input submitted - run resumed.", null);
        }
        com.agenticai.core.workflow.humaninput.WorkflowInputRequestService service =
                inputRequestService.getIfAvailable();
        if (service == null) {
            // Slim test contexts without the JPA layer - same graceful
            // degradation posture as the ObjectProvider dependencies above.
            return NodeExecutionResult.ok(
                    input, "Human-input service unavailable - passed input through.", null);
        }
        var request =
                service.createPending(
                        java.util.UUID.fromString(runId),
                        java.util.UUID.fromString(workflowDefinitionId),
                        workflowDefinitionName,
                        node,
                        input,
                        requestedBy);
        return NodeExecutionResult.halted(
                WorkflowRunStatus.AWAITING_INPUT,
                "Awaiting human input (request " + request.getId() + ").");
    }
}
