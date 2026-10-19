package com.agenticai.core.workflow.temporal;

import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.Set;

/**
 * Everything {@link WorkflowRunWorkflowImpl} needs to replay deterministically -
 * a plain, Jackson-serializable snapshot (Temporal's default data converter),
 * never a live JPA entity or {@code TenantContext} reference (see {@code
 * WorkflowNodeActivitiesImpl}'s javadoc for why tenant identity has to travel
 * as explicit data like this rather than an inherited {@code ThreadLocal}).
 *
 * <p>{@code runId} is minted by {@code TemporalWorkflowExecutionEngine}
 * BEFORE this workflow starts (same "the run row already exists, using its
 * own id as the correlation id" pattern the legacy {@code
 * WorkflowExecutionEngine} already uses) - it also becomes this workflow
 * execution's Temporal Workflow Id, which is what makes re-submitting the
 * same {@code runId} idempotent at the Temporal layer (a duplicate start
 * with the same Workflow Id attaches to the existing execution instead of
 * starting a second one).
 */
public record WorkflowRunInput(
    String runId,
    String workflowDefinitionId,
    String workflowName,
    WorkflowGraphDto graph,
    // Per-node workflow triggers - the id of the Trigger node this run
    // should start from, or null to fall back to "the first Trigger node in
    // the graph" (manual Run button / schedule trigger - see
    // WorkflowExecutionEngine#execute's identical parameter for the full
    // rationale, mirrored here for the Temporal-backed execution path).
    String startNodeId,
    String input,
    String requestedBy,
    String tenantId,
    String subject,
    Set<String> roles,
    int maxStepsPerRun,
    int stepTimeoutSeconds,
    // Retries AFTER the first attempt (0-5, see WorkflowDefinition#maxRetryAttempts's
    // javadoc) - WorkflowRunWorkflowImpl adds 1 before handing this to
    // Temporal's RetryOptions#setMaximumAttempts, which treats a raw 0 as
    // UNLIMITED retries, the opposite of this field's meaning.
    int maxRetryAttempts,
    // C1: the workflow_version ordinal resolved (label-first) at run start.
    // Carried in the workflow input - NOT re-read from the DB mid-run - so a
    // replay/continuation always sees the version the run actually started
    // on even if PRODUCTION moved meanwhile. Null for runs that predated
    // stamping. (The graph itself is already pinned: `graph` above is the
    // resolved snapshot document serialized into this input.)
    Integer workflowVersion,
    // How the run was started: "production" (or null, for inputs serialised before this field existed) or
    // "draft" - see RunMode. Carried in the input so a replay sees the mode the run started in, and stamped on
    // every node activity, which is the only way the executor is told it may serve a pin.
    String runMode
) {

    /** Pre-runMode arity: a production run. */
    public WorkflowRunInput(
        String runId, String workflowDefinitionId, String workflowName, WorkflowGraphDto graph, String startNodeId,
        String input, String requestedBy, String tenantId, String subject, Set<String> roles, int maxStepsPerRun,
        int stepTimeoutSeconds, int maxRetryAttempts, Integer workflowVersion
    ) {
        this(runId, workflowDefinitionId, workflowName, graph, startNodeId, input, requestedBy, tenantId, subject,
            roles, maxStepsPerRun, stepTimeoutSeconds, maxRetryAttempts, workflowVersion, null);
    }
}
