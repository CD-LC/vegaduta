package com.agenticai.core.workflow;

/**
 * Governance guardrail mirroring {@code ToolStepLimitExceededException} in
 * the guardrails module: a graph with a cycle {@link OrchestratorService}
 * failed to catch at validation time (or one added after validation, before
 * a run started) must not be allowed to loop forever and burn LLM spend -
 * the execution engine hard-stops after {@code app.workflow.max-steps-per-run}
 * node executions (default 200) and fails the run cleanly instead.
 */
public class WorkflowStepLimitExceededException extends RuntimeException {

    public WorkflowStepLimitExceededException(int maxSteps) {
        super("Workflow run exceeded the maximum of " + maxSteps + " node executions - aborted "
            + "to guard against an unbounded loop. Check the graph for a cycle Orchestrator "
            + "validation may have missed.");
    }
}
