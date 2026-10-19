package com.agenticai.core.agents.job.schedule.dto;

import com.agenticai.core.agents.job.schedule.AgentScheduleRun;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /api/agents/{agentId}/schedule/runs}.
 *
 * @param status ENQUEUED, RUNNING, SUCCEEDED, FAILED or SKIPPED
 * @param jobStatus for an agent-target run, the {@code agent_job}'s CURRENT status
 *     resolved at read time (the row itself is written once at enqueue)
 * @param jobId agent job id (AGENT targets)
 * @param workflowRunId workflow run id (WORKFLOW targets)
 * @param trigger SCHEDULE (the tick) or MANUAL (run-now)
 * @param sentinelOutcome what {@code AgentSentinelSweep} judged of a sentinel
 *     firing (QUIET/NOTIFIED/PROPOSED/NO_VERDICT/JOB_FAILED/JOB_GONE); NULL on
 *     non-sentinel runs and on sentinel runs the sweep has not reached yet
 * @param sentinelOutcomeNote audit context for the outcome (approval request
 *     id for PROPOSED, terminal job state for JOB_FAILED, ...)
 */
public record AgentScheduleRunResponse(
    UUID id,
    String trigger,
    String targetType,
    String status,
    String jobStatus,
    Instant startedAt,
    Instant finishedAt,
    UUID jobId,
    UUID workflowRunId,
    String error,
    String sentinelOutcome,
    String sentinelOutcomeNote
) {

    public static AgentScheduleRunResponse from(AgentScheduleRun run, String jobStatus) {
        return new AgentScheduleRunResponse(
            run.getId(),
            run.getTriggerKind(),
            run.getTargetType(),
            run.getStatus(),
            jobStatus,
            run.getStartedAt(),
            run.getFinishedAt(),
            run.getJobId(),
            run.getWorkflowRunId(),
            run.getError(),
            run.getSentinelOutcome(),
            run.getSentinelOutcomeNote()
        );
    }
}
