package com.agenticai.core.agents.scheduledtask;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for {@code /api/scheduled-tasks}. Plain records; every rule lives in the service. */
public final class ScheduledTaskDtos {

    private ScheduledTaskDtos() {
    }

    /**
     * Create / replace a task.
     *
     * @param frequency daily | weekdays | weekly | monthly | custom
     * @param time      HH:mm (24h), every frequency but custom
     * @param day       weekly: Mon..Sun; monthly: 1-28 or last
     * @param cron      custom only: a 5-field cron
     * @param delivery  in_app (default) | email | whatsapp | whatsapp_qr | telegram | slack |
     *     signal | matrix | imessage | a2a | voice
     * @param maxRuns   optional: stop after this many runs
     * @param expiresAt optional: stop after this instant
     */
    public record TaskRequest(
        @NotNull UUID agentId,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 4000) String prompt,
        @NotBlank @Size(max = 12) String frequency,
        @Size(max = 5) String time,
        @Size(max = 8) String day,
        @Size(max = 64) String cron,
        @Size(max = 64) String timezone,
        @Size(max = 12) String delivery,
        @Size(max = 255) String deliveryTo,
        Integer maxRuns,
        Instant expiresAt
    ) {
    }

    /** A schedule on its own, for the live "next runs" preview of a custom cron. */
    public record PreviewRequest(
        @NotBlank @Size(max = 12) String frequency,
        @Size(max = 5) String time,
        @Size(max = 8) String day,
        @Size(max = 64) String cron,
        @Size(max = 64) String timezone
    ) {
    }

    public record PreviewResponse(String cron, String description, String timezone, List<Instant> nextRuns) {
    }

    public record Capabilities(
        boolean enabled,
        int maxTasks,
        int minIntervalMinutes,
        int maxPromptChars,
        List<String> deliveryChannels
    ) {
    }

    public record TaskResponse(
        UUID id,
        UUID agentId,
        String name,
        String prompt,
        String frequency,
        String time,
        String day,
        String cron,
        String timezone,
        String scheduleDescription,
        String delivery,
        String deliveryTo,
        boolean enabled,
        String disabledReason,
        Instant nextRunAt,
        List<Instant> upcomingRuns,
        Instant lastRunAt,
        String lastStatus,
        UUID lastJobId,
        int consecutiveFailures,
        long runCount,
        Integer maxRuns,
        Instant expiresAt,
        String ownerSubject,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record RunResponse(
        UUID id,
        UUID taskId,
        String trigger,
        String status,
        int attempt,
        Instant startedAt,
        Instant finishedAt,
        UUID jobId,
        BigDecimal costUsd,
        String deliveryStatus,
        String error
    ) {

        public static RunResponse from(ScheduledTaskRun run) {
            return new RunResponse(
                run.getId(), run.getTaskId(), run.getTriggerKind(), run.getStatus(), run.getAttempt(),
                run.getStartedAt(), run.getFinishedAt(), run.getJobId(), run.getCostUsd(),
                run.getDeliveryStatus(), run.getError());
        }
    }
}
