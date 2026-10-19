package com.agenticai.core.workflow.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Schedule trigger (#166). The frontend builds {@code cron} itself from a
 * simple hourly/daily/weekly preset picker (see WorkflowCanvas.js) rather
 * than asking a person to type a raw cron expression - this DTO still takes
 * the final cron string so the backend has one clear validation point
 * ({@code WorkflowService#enableSchedule}) regardless of how the frontend
 * ever decides to build it.
 */
public record WorkflowScheduleRequest(@NotBlank String cron) {
}
