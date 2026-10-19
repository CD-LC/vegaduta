package com.agenticai.core.agents.tools.scheduling;

import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Port that builds the {@code schedule_task} tool for one agent.
 *
 * <p>Scheduled tasks live in {@code core/agents-jobs} (ADR-0023), which depends on {@code
 * core/agents}; the tool resolver here ({@code AgentScheduledTaskToolResolver}) must not depend
 * back on it. {@code ScheduleTaskToolCallbackFactory} in {@code agents-jobs} implements this over
 * {@code ScheduledTaskService} and {@code ScheduledTaskSettings}.
 */
public interface ScheduleTaskToolFactory {

    /** The tool's name, the per-agent allow-list entry that opts an agent in. */
    String TOOL_NAME = "schedule_task";

    /**
     * The tool bound to {@code agentId}, or {@code null} when scheduled tasks are switched off
     * ({@code app.scheduled-tasks.enabled}) or their service is not available in this context.
     */
    ToolCallback createIfEnabled(UUID agentId);
}
