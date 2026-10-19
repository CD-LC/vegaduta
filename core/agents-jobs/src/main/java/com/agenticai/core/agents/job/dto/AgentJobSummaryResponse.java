package com.agenticai.core.agents.job.dto;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * List-row view. Carries {@code goalPreview} rather than the whole goal (which
 * is a TEXT column and can be thousands of characters) and {@code progressNote}
 * rather than a progress history, so rendering fifty rows is one query and one
 * modest payload - which is the entire reason {@code agent_job.progress_note} is
 * denormalized onto the row in the first place.
 *
 * <p>{@code agentName} is resolved by the caller, not by a lazy association: a
 * list of fifty jobs across ten agents should cost ten name lookups from an
 * already-loaded map, never fifty.
 *
 * <p>{@code parkedAt}/{@code approvalDeadlineAt} are carried on the LIST row,
 * not just the single-job view, because the operator list is where "why has this
 * one not moved in two hours?" is actually asked - and a row that is waiting on
 * a named human by a known deadline is a different answer from a row that is
 * stuck. Both are null for every job that never parked, which is nearly all of
 * them.
 */
public record AgentJobSummaryResponse(
    UUID id,
    UUID agentId,
    String agentName,
    AgentJobStatus status,
    String goalPreview,
    String progressNote,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    String errorCode,
    Instant parkedAt,
    Instant approvalDeadlineAt
) {

    private static final int GOAL_PREVIEW_CHARS = 160;

    public static AgentJobSummaryResponse from(AgentJob job, String agentName) {
        return new AgentJobSummaryResponse(
            job.getId(),
            job.getAgentId(),
            agentName,
            job.getStatus(),
            preview(job.getGoal()),
            job.getProgressNote(),
            job.getCreatedAt(),
            job.getStartedAt(),
            job.getFinishedAt(),
            job.getErrorCode(),
            job.getParkedAt(),
            job.getApprovalDeadlineAt()
        );
    }

    private static String preview(String goal) {
        if (goal == null) {
            return null;
        }
        return goal.length() <= GOAL_PREVIEW_CHARS ? goal : goal.substring(0, GOAL_PREVIEW_CHARS);
    }
}
