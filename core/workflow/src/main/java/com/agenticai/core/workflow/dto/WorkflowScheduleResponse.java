package com.agenticai.core.workflow.dto;

import java.time.Instant;

/**
 * Schedule trigger (#166). {@code cron} is a standard 5-field cron
 * expression interpreted in UTC (see {@code WorkflowScheduler}); {@code
 * nextRunAt} is the pre-computed next fire time, shown to the tenant so
 * "when will this actually run" never requires reading a cron expression by
 * eye.
 */
public record WorkflowScheduleResponse(String cron, boolean enabled, Instant nextRunAt) {
}
