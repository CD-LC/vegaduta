package com.agenticai.core.workflow.temporal;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import java.util.List;
import java.util.Set;

/**
 * Input to {@link WorkflowNodeActivities#recordProgress} - the Temporal
 * path's equivalent of the legacy {@code WorkflowExecutionEngine}'s
 * "{@code run.updateTrace(...); runRepository.save(run);
 * eventPublisher.publish(...)}" lines after every node, and again (with
 * {@code terminal=true}) once the run finishes. Every DB write / Kafka
 * publish this platform does as a side effect of running a workflow lives
 * in this one activity call so {@link WorkflowRunWorkflowImpl} itself never
 * touches a repository or publisher directly (workflow code must be
 * side-effect-free / deterministic - see that class's javadoc).
 */
public record RunProgressInput(
    String runId,
    String workflowDefinitionId,
    String workflowName,
    String status,
    List<NodeTraceEntry> trace,
    String errorMessage,
    String outputFileName,
    String outputData,
    boolean terminal,
    String lastNodeId,
    String lastNodeType,
    String tenantId,
    String subject,
    Set<String> roles
) {
}
