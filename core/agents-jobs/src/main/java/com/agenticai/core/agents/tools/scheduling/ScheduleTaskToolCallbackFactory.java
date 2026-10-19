package com.agenticai.core.agents.tools.scheduling;

import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSettings;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Implements {@link ScheduleTaskToolFactory} for the tool resolver in {@code core/agents}: builds a
 * {@link ScheduleTaskToolCallback} when {@code app.scheduled-tasks.enabled} is on and the service
 * bean exists, otherwise returns {@code null}.
 *
 * <p>Both collaborators are {@link ObjectProvider}s and are resolved per call, exactly as the
 * resolver resolved them before scheduled tasks moved to {@code core/agents-jobs} (ADR-0023): no
 * start-up ordering between the tool provider and the services behind it.
 */
@Component
public class ScheduleTaskToolCallbackFactory implements ScheduleTaskToolFactory {

    private final ObjectProvider<ScheduledTaskService> service;
    private final ObjectProvider<ScheduledTaskSettings> settings;

    public ScheduleTaskToolCallbackFactory(
            ObjectProvider<ScheduledTaskService> service,
            ObjectProvider<ScheduledTaskSettings> settings) {
        this.service = service;
        this.settings = settings;
    }

    @Override
    public ToolCallback createIfEnabled(UUID agentId) {
        ScheduledTaskSettings s = settings == null ? null : settings.getIfAvailable();
        if (s == null || !s.isEnabled()) {
            return null;
        }
        ScheduledTaskService svc = service == null ? null : service.getIfAvailable();
        return svc == null ? null : new ScheduleTaskToolCallback(svc, s, agentId);
    }
}
