package com.agenticai.core.agents.tools.coding;

import com.agenticai.core.agents.coding.CodingTaskService;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Implements {@link CodingTaskToolFactory} for the tool resolver in {@code core/agents}: builds a
 * {@link CodingTaskToolCallback} when the {@link CodingTaskService} bean exists and reports itself
 * enabled, otherwise returns {@code null}.
 *
 * <p>The service is an {@link ObjectProvider} resolved per call, exactly as the resolver resolved
 * it before the coding harness moved to {@code core/agents-studio} (ADR-0023).
 */
@Component
public class CodingTaskToolCallbackFactory implements CodingTaskToolFactory {

    private final ObjectProvider<CodingTaskService> service;

    public CodingTaskToolCallbackFactory(ObjectProvider<CodingTaskService> service) {
        this.service = service;
    }

    @Override
    public ToolCallback createIfEnabled(UUID agentId) {
        CodingTaskService svc = service == null ? null : service.getIfAvailable();
        if (svc == null || !svc.isEnabled()) {
            return null;
        }
        return new CodingTaskToolCallback(svc, agentId);
    }
}
