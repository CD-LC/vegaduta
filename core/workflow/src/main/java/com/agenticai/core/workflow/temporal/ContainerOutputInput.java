package com.agenticai.core.workflow.temporal;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.Set;

/**
 * Input to {@link WorkflowNodeActivities#recordContainerOutput}: the aggregate output of a loop / parallel /
 * fan_out node, which never passes through {@code executeNode}. Tenant identity rides along for the same reason
 * it does on {@link NodeExecutionActivityInput} (activities run on worker threads with no request context).
 */
public record ContainerOutputInput(
    String workflowDefinitionId,
    String workflowDefinitionName,
    String runId,
    String requestedBy,
    WorkflowNodeDto node,
    String output,
    String tenantId,
    String subject,
    Set<String> roles
) {
}
