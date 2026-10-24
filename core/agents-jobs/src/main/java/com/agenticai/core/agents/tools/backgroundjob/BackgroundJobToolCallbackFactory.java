package com.agenticai.core.agents.tools.backgroundjob;

import com.agenticai.core.agents.job.AgentJobService;
import com.agenticai.core.agents.tools.scheduling.BackgroundJobToolFactory;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Implements {@link BackgroundJobToolFactory} for the tool resolver in {@code core/agents}: builds
 * a {@link BackgroundJobToolCallback} when the job service exists in this Spring context (the API
 * shape does; a slim test context may not), otherwise returns {@code null}.
 *
 * <p>{@link ObjectProvider}, resolved per call, exactly as the scheduled-task factory does: no
 * start-up ordering between the tool provider and the job service.
 */
@Component
public class BackgroundJobToolCallbackFactory implements BackgroundJobToolFactory {

    private final ObjectProvider<AgentJobService> jobService;

    public BackgroundJobToolCallbackFactory(ObjectProvider<AgentJobService> jobService) {
        this.jobService = jobService;
    }

    @Override
    public ToolCallback createIfEnabled(UUID agentId) {
        AgentJobService svc = jobService == null ? null : jobService.getIfAvailable();
        return svc == null ? null : new BackgroundJobToolCallback(svc, agentId);
    }
}
