package com.agenticai.core.workflow;

import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** "voice_menu": halts for a DTMF digit and routes on the matched branch when resumed. */
@Component
class VoiceMenuNodeHandler implements NodeHandler {

    private final ObjectProvider<
                    com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService>
            voiceMenuRequestService;
    private final ObjectMapper objectMapper;

    VoiceMenuNodeHandler(
            ObjectProvider<com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService>
                    voiceMenuRequestService,
            ObjectMapper objectMapper) {
        this.voiceMenuRequestService = voiceMenuRequestService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("voice_menu");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeVoiceMenuNode(
                inv.workflowDefinitionId(),
                inv.workflowDefinitionName(),
                inv.runId(),
                inv.node(),
                inv.input(),
                inv.requestedBy());
    }

    /**
     * Voice Menu node (V308) - see {@link WorkflowNodeType#VOICE_MENU}'s javadoc for the overall
     * design; the key point repeated here: this is the exact same halt/resume shape {@link
     * #executeHumanInputNode} uses, just swapped to {@code WorkflowVoiceMenuRequestService} and a
     * digit, not a form, as the thing being waited on.
     *
     * <p>On first execution: snapshots the node's prompt/digit/timeout/retry/ branch config onto a
     * PENDING row and halts {@code AWAITING_VOICE_INPUT} - {@code TwilioVoiceWebhookController}
     * reads that row (via {@code WorkflowTriggerPort}) to render the actual DTMF {@code <Gather>}
     * TwiML.
     *
     * <p>On resume (grant consumed): {@code input} is the resume envelope {@code
     * WorkflowVoiceMenuRequestService.collectDigits} built - {@code {"digits": "...", "branch":
     * "...", "input": <upstream>}} - parsed here so the matched {@code branch} becomes this
     * result's {@code branch()} and {@link WorkflowExecutionEngine}'s existing edge {@code
     * sourceHandle} resolution (the same mechanism Condition/Supervisor already use) routes the
     * graph on it with zero engine changes.
     */
    private NodeExecutionResult executeVoiceMenuNode(
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            WorkflowNodeDto node,
            String input,
            String requestedBy) {
        if (ApprovalReplayGrant.consume(
                com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService
                        .REPLAY_ACTION_TYPE)) {
            try {
                JsonNode envelope = objectMapper.readTree(input);
                String branch =
                        envelope.hasNonNull("branch") ? envelope.get("branch").asString() : null;
                String digits = envelope.path("digits").asString("");
                return NodeExecutionResult.ok(
                        input,
                        digits.isBlank()
                                ? "No digits pressed - routed to '" + branch + "'."
                                : "Caller pressed '" + digits + "' - routed to '" + branch + "'.",
                        branch);
            } catch (Exception e) {
                return NodeExecutionResult.ok(input, "Voice menu resumed.", null);
            }
        }
        com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService service =
                voiceMenuRequestService.getIfAvailable();
        if (service == null) {
            // Slim test contexts without the JPA layer - same graceful
            // degradation posture as the ObjectProvider dependencies above.
            return NodeExecutionResult.ok(
                    input, "Voice menu service unavailable - passed input through.", null);
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
                WorkflowRunStatus.AWAITING_VOICE_INPUT,
                "Awaiting DTMF input for voice menu (request " + request.getId() + ").");
    }
}
