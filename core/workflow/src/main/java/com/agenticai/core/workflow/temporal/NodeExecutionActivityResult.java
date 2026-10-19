package com.agenticai.core.workflow.temporal;

import java.util.List;

/**
 * Mirrors {@code WorkflowNodeExecutor.NodeExecutionResult} field-for-field,
 * plus {@code startedAtEpochMilli}/{@code completedAtEpochMilli} - captured
 * here, inside the Activity (where real wall-clock reads are allowed),
 * rather than in {@link WorkflowRunWorkflowImpl} itself. A Temporal Workflow
 * method is replayed to rebuild state after a worker crash/restart, so any
 * direct {@code Instant.now()}/{@code System.currentTimeMillis()} call
 * inside the workflow method itself would produce a different value on
 * every replay - one of a handful of non-deterministic operations Temporal
 * workflow code must never do directly (see {@code
 * io.temporal.workflow.Workflow}'s own {@code currentTimeMillis()}/{@code
 * newRandom()} replacements for the ones that ARE safe). Activities are not
 * replayed this way, so timestamping here is the correct place for it.
 */
public record NodeExecutionActivityResult(
    String output,
    String status,
    String summary,
    String branch,
    String haltRun,
    List<String> logLines,
    String fileName,
    String fileData,
    long startedAtEpochMilli,
    long completedAtEpochMilli
) {
}
