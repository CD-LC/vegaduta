package com.agenticai.core.agents.job.dto;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Full single-job view.
 *
 * <p>{@code terminal} is projected explicitly rather than left for the client to derive from {@code
 * status}. Every consumer needs "should I keep polling?", and every consumer deriving it
 * independently is how AWAITING_APPROVAL ends up treated as finished in one of them - the exact
 * mistake {@code WorkflowRunStatus}'s javadoc records the workflow engine having to correct. One
 * server-side answer, no client-side lists of status names to keep in sync.
 *
 * <p>{@code resultJson} is a parsed {@link JsonNode}, not the raw string, so a structured result
 * arrives as real JSON rather than a JSON-encoded string the client has to parse a second time.
 *
 * <p>{@code parkedAt}/{@code approvalDeadlineAt}/{@code parkCount} are read-only and exist so a job
 * waiting on a human is DISTINGUISHABLE from a hung one over the API. Without them a parked job is
 * just "not terminal, no progress for two hours", which is the same thing an operator sees for a
 * job that is genuinely stuck - and telling those two apart is the entire point of parking.
 */
public record AgentJobResponse(
        UUID id,
        UUID agentId,
        String goal,
        AgentJobStatus status,
        boolean terminal,
        AgentJobOrigin origin,
        String originRef,
        String sessionId,
        String requestedBy,
        String progressNote,
        Instant progressUpdatedAt,
        String resultText,
        JsonNode resultJson,
        Integer schemaAttempts,
        BigDecimal costUsd,
        String errorCode,
        String errorMessage,
        int timeoutSeconds,
        Instant deadlineAt,
        int turnsUsed,
        int maxTurns,
        boolean cancelRequested,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        UUID scheduleId,
        Instant scheduleOccurrenceAt,
        Instant parkedAt,
        Instant approvalDeadlineAt,
        int parkCount) {

    /**
     * @param objectMapper used only to re-parse the stored {@code result_json} text. A parse
     *     failure degrades to {@code null} rather than throwing: the column is written by the
     *     platform, so unparseable content there is a platform bug, and failing the whole GET would
     *     hide the job's real status, result text and error from the operator trying to diagnose
     *     it.
     */
    public static AgentJobResponse from(AgentJob job, ObjectMapper objectMapper) {
        return new AgentJobResponse(
                job.getId(),
                job.getAgentId(),
                job.getGoal(),
                job.getStatus(),
                job.getStatus().isTerminal(),
                job.getOrigin(),
                job.getOriginRef(),
                job.getSessionId(),
                job.getRequestedBy(),
                job.getProgressNote(),
                job.getProgressUpdatedAt(),
                job.getResultText(),
                parseOrNull(job.getResultJson(), objectMapper),
                job.getSchemaAttempts(),
                job.getCostUsd(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getTimeoutSeconds(),
                job.getDeadlineAt(),
                job.getTurnsUsed(),
                job.getMaxTurns(),
                job.isCancelRequested(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getScheduleId(),
                job.getScheduleOccurrenceAt(),
                job.getParkedAt(),
                job.getApprovalDeadlineAt(),
                job.getParkCount());
    }

    private static JsonNode parseOrNull(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception unparseable) {
            return null;
        }
    }
}
