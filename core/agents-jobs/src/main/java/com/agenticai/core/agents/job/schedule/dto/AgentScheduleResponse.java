package com.agenticai.core.agents.job.schedule.dto;

import com.agenticai.core.agents.job.schedule.AgentSchedule;
import java.time.Instant;
import java.util.UUID;

/**
 * What {@code GET/PUT/DELETE /api/agents/{agentId}/schedule} returns.
 *
 * <p><b>The skip fields are the point of this DTO, not decoration.</b> A skip is
 * a normal outcome of the overlap guard - a five-minute schedule against a
 * twenty-minute job skips roughly three times per run by design - so the
 * question an operator actually arrives with is never "did it fire?" but "is
 * this broken, or is it still working?". {@code lastSkipReason} +
 * {@code consecutiveSkipCount} + {@code lastJobStatus} answer that in one
 * request. Without them the honest answer would only exist in a log line.
 *
 * <p>Carries no secret of any kind, which is why {@code GET} is deliberately not
 * admin-gated in {@code SecurityConfig} while {@code PUT}/{@code DELETE} are -
 * the same read-open split as {@code GET /api/workflows/*}{@code /schedule}.
 *
 * @param lastJobStatus the status of {@code lastJobId} at read time, resolved
 *     live rather than denormalized onto the schedule row: a denormalized copy
 *     would be written once at fire time and would then say {@code QUEUED}
 *     forever, which is worse than not showing it at all.
 */
public record AgentScheduleResponse(
    String cron,
    Integer intervalSeconds,
    String targetType,
    UUID workflowId,
    String timezone,
    String goalTemplate,
    boolean enabled,
    Instant nextRunAt,
    Instant lastFiredAt,
    UUID lastJobId,
    String lastJobStatus,
    Instant lastSkippedAt,
    String lastSkipReason,
    int consecutiveSkipCount,
    long totalFireCount,
    long totalSkipCount,
    String createdBy,
    Instant updatedAt,
    boolean sentinel
) {

    public static AgentScheduleResponse from(AgentSchedule schedule, String lastJobStatus) {
        return new AgentScheduleResponse(
            schedule.getCron(),
            schedule.getIntervalSeconds(),
            schedule.getTargetType().name(),
            schedule.getWorkflowId(),
            schedule.getTimezone(),
            schedule.getGoalTemplate(),
            schedule.isEnabled(),
            schedule.getNextRunAt(),
            schedule.getLastFiredAt(),
            schedule.getLastJobId(),
            lastJobStatus,
            schedule.getLastSkippedAt(),
            schedule.getLastSkipReason() == null ? null : schedule.getLastSkipReason().name(),
            schedule.getConsecutiveSkipCount(),
            schedule.getTotalFireCount(),
            schedule.getTotalSkipCount(),
            schedule.getCreatedBy(),
            schedule.getUpdatedAt(),
            schedule.isSentinelEnabled()
        );
    }
}
