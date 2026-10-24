package com.agenticai.core.agents.tools.code;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalExecutionResultWriter;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Enables a model-authored custom code tool, ONLY once a human has approved the request (or an
 * explicit per-tenant {@code ApprovalPolicyOverride} has opted this action type out of approval -
 * see {@code ApprovalPolicyService}) - the registered {@link ApprovalActionExecutor} for {@link
 * #ACTION_TYPE}. {@link CreateCustomToolToolCallback} creates the tool as a DISABLED draft and
 * records this action type through {@code HumanApprovalGateService#evaluateInTurn}; this class only
 * runs once {@code HumanApprovalGateService#approve} invokes it.
 *
 * <p><b>Why the approval lives here and not in the tool-call gate.</b> The dispatch gate's approval
 * modes are per-agent: a fully-autonomous ({@code NEVER_GATE}) agent is deliberately exempt from
 * the classification gate's confirmation requirement. Model-authored code must not inherit that
 * exemption - a created tool is callable by EVERY agent in the workspace the moment it is enabled,
 * so the enable step is an approval action with its own executor: no autonomy tier, gate mode or
 * taint setting can produce an enabled tool without a human decision in between. The draft write
 * itself needs no such gate: a disabled draft cannot be invoked, is reversible by deletion, and is
 * bounded by the tenant's custom-tool quota.
 *
 * <p>Following {@code RunCodeWorkspaceApprovalExecutor}'s contract: {@link
 * HumanApprovalGateService} calls {@link #execute} outside any transaction and persists nothing on
 * a plain successful return, so this executor records its own execution result explicitly - the
 * Approvals screen then shows <i>which</i> tool was enabled, not merely that the request was
 * approved.
 */
@Component
public class CustomCodeToolEnableApprovalExecutor implements ApprovalActionExecutor {

    /** The action type, also listed in {@code ApprovalPolicyService}'s default-gated patterns. */
    public static final String ACTION_TYPE = "custom_code_tool.enable";

    private static final Logger log =
            LoggerFactory.getLogger(CustomCodeToolEnableApprovalExecutor.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CustomCodeToolService service;
    private final ApprovalExecutionResultWriter resultWriter;

    public CustomCodeToolEnableApprovalExecutor(
            CustomCodeToolService service, ApprovalExecutionResultWriter resultWriter) {
        this.service = service;
        this.resultWriter = resultWriter;
    }

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    @Override
    public void execute(ApprovalRequest request) {
        UUID toolId = toolIdOf(request.getPayloadJson());
        CustomCodeTool enabled = service.setEnabled(toolId, true);
        log.info(
                "Custom code tool '{}' ({}) enabled after approval of request {}",
                enabled.getToolName(),
                toolId,
                request.getId());
        // Same contract RunCodeWorkspaceApprovalExecutor documents: execute() runs outside a
        // transaction, so the outcome is persisted and announced here explicitly - through the
        // approval area's writer, not its repository (feature areas keep their tables private).
        resultWriter.record(
                request,
                "enabled tool '" + enabled.getToolName() + "' (" + toolId + ") for the workspace");
    }

    @Override
    public void onRejected(ApprovalRequest request, String decidedBy, String reason) {
        // The draft row stays, disabled, for the tenant to fix or delete from the
        // custom-code-tools editor - deleting it here would discard work a human may simply
        // have wanted to review later.
        log.info(
                "Approval {} to enable a custom code tool was rejected by {} - the draft remains"
                        + " disabled",
                request.getId(),
                decidedBy);
    }

    private static UUID toolIdOf(String payloadJson) {
        try {
            JsonNode node = MAPPER.readTree(payloadJson == null ? "{}" : payloadJson);
            // evaluateInTurn may wrap the payload in a conversation envelope; the tool's own
            // fields stay top-level either way (see HumanApprovalGateService#withReplayEnvelope).
            String id = node.path("toolId").asText(null);
            if (id == null) {
                id = node.path("payload").path("toolId").asText(null);
            }
            if (id == null) {
                throw new IllegalArgumentException(
                        "Approval request payload has no toolId to enable");
            }
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Approval request payload is not readable: " + e.getMessage(), e);
        }
    }
}
