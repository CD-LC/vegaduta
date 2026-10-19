package com.agenticai.core.workflow.temporal;

/**
 * {@link WorkflowRunWorkflow}'s return value - deliberately minimal, since
 * the authoritative record of what happened is the {@code workflow_run} row
 * itself (written incrementally by {@code WorkflowNodeActivitiesImpl.recordProgress},
 * the same row the legacy engine writes). {@code
 * TemporalWorkflowExecutionEngine} re-reads that row after this result comes
 * back rather than trusting this value alone, so a caller who only cares
 * about "did this complete" can await this, while anyone who needs the full
 * trace/output still goes through {@code GET .../runs/{runId}} exactly as
 * before.
 */
public record WorkflowRunResult(
    String status,
    String errorMessage,
    String outputFileName,
    String outputData
) {
}
