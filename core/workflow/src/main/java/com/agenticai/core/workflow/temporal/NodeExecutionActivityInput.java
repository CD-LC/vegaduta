package com.agenticai.core.workflow.temporal;

import com.agenticai.core.workflow.ExpressionScope;
import com.agenticai.core.workflow.WorkflowIntelligenceSettings;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.Set;

/**
 * Input to {@link WorkflowNodeActivities#executeNode} - one call per graph
 * node. Carries the calling tenant's identity explicitly ({@code tenantId}/
 * {@code subject}/{@code roles}, captured once in {@code
 * TemporalWorkflowExecutionEngine} from the HTTP request thread's real
 * {@code TenantContext}) because Temporal Activities run on worker threads
 * with no relationship to whatever thread originally started the workflow -
 * see {@code WorkflowNodeActivitiesImpl}'s javadoc for the full explanation
 * of why this is not optional (the exact bug class task #63 fixed for the
 * MCP tool-call path applies here too).
 */
public record NodeExecutionActivityInput(
    String workflowDefinitionId,
    String workflowDefinitionName,
    String runId,
    WorkflowNodeDto node,
    String input,
    String requestedBy,
    String tenantId,
    String subject,
    Set<String> roles,
    WorkflowIntelligenceSettings intelligenceSettings,
    /**
     * The {@code "workflow.node.<type>"} grant this execution should carry
     * past {@code WorkflowNodeExecutor}'s approval gate, or {@code null} for
     * a normal (non-resumed) execution. This is the durable-boundary
     * equivalent of the legacy engine's same-thread {@code
     * ApprovalReplayGrant} - a ThreadLocal can't cross the
     * workflow-code/activity-process line, so on the signal-resume path
     * {@code WorkflowRunWorkflowImpl} serializes the granted action type
     * here and {@code WorkflowNodeActivitiesImpl} re-establishes the grant
     * on the activity thread (and only for the single node execution it was
     * issued for - the grant is cleared in a finally block exactly like the
     * legacy path).
     */
    String approvalReplayActionType,
    /**
     * Loop / fan_out iteration facts for {@code $item}/{@code $index}/{@code $items} and the run-data
     * {@code seq}, or {@code null} for an ordinary node. Added last and nullable so a payload serialized
     * before it existed still deserializes; it only changes the activity's input payload, not the
     * workflow's command sequence, so it needs no {@code Workflow.getVersion} guard.
     */
    ExpressionScope expressionScope
) {
    /**
     * Pre-scope arity (11 components): keeps every existing construction site compiling.
     */
    public NodeExecutionActivityInput(
        String workflowDefinitionId,
        String workflowDefinitionName,
        String runId,
        WorkflowNodeDto node,
        String input,
        String requestedBy,
        String tenantId,
        String subject,
        Set<String> roles,
        WorkflowIntelligenceSettings intelligenceSettings,
        String approvalReplayActionType
    ) {
        this(workflowDefinitionId, workflowDefinitionName, runId, node, input,
            requestedBy, tenantId, subject, roles, intelligenceSettings, approvalReplayActionType, null);
    }

    /**
     * Convenience for the overwhelming majority of call sites that are not
     * replaying an approval - keeps the pre-signal arity so loop/parallel/
     * fan-out/first-node construction sites stay untouched.
     */
    public NodeExecutionActivityInput(
        String workflowDefinitionId,
        String workflowDefinitionName,
        String runId,
        WorkflowNodeDto node,
        String input,
        String requestedBy,
        String tenantId,
        String subject,
        Set<String> roles,
        WorkflowIntelligenceSettings intelligenceSettings
    ) {
        this(workflowDefinitionId, workflowDefinitionName, runId, node, input,
            requestedBy, tenantId, subject, roles, intelligenceSettings, null, null);
    }

    /** Returns a copy carrying an approval-replay grant for {@code actionType}. */
    public NodeExecutionActivityInput withApprovalReplay(String actionType) {
        return new NodeExecutionActivityInput(
            workflowDefinitionId, workflowDefinitionName, runId, node, input,
            requestedBy, tenantId, subject, roles, intelligenceSettings, actionType, expressionScope);
    }

    /** Returns a copy carrying loop / fan_out iteration facts. */
    public NodeExecutionActivityInput withScope(ExpressionScope scope) {
        return new NodeExecutionActivityInput(
            workflowDefinitionId, workflowDefinitionName, runId, node, input,
            requestedBy, tenantId, subject, roles, intelligenceSettings, approvalReplayActionType, scope);
    }
}
