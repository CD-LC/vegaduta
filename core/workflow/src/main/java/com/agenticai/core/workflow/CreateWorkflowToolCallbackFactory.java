package com.agenticai.core.workflow;

import com.agenticai.core.agents.tools.workflowdraft.WorkflowDraftToolFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implements {@link WorkflowDraftToolFactory} for the tool resolver in {@code core/agents-tools}:
 * builds a {@link CreateWorkflowToolCallback} when {@code app.tools.create-workflow.enabled} is on
 * and both workflow services exist, otherwise returns {@code null}. The services are {@link
 * ObjectProvider}s resolved per call, so there is no start-up ordering against the tool provider.
 */
@Component
public class CreateWorkflowToolCallbackFactory implements WorkflowDraftToolFactory {

    private final ObjectProvider<WorkflowCrudService> crud;
    private final ObjectProvider<WorkflowGraphGeneratorService> generator;
    private final boolean enabled;

    public CreateWorkflowToolCallbackFactory(
            ObjectProvider<WorkflowCrudService> crud,
            ObjectProvider<WorkflowGraphGeneratorService> generator,
            @Value("${app.tools.create-workflow.enabled:true}") boolean enabled) {
        this.crud = crud;
        this.generator = generator;
        this.enabled = enabled;
    }

    @Override
    public ToolCallback createIfEnabled() {
        if (!enabled) {
            return null;
        }
        WorkflowCrudService crudService = crud.getIfAvailable();
        WorkflowGraphGeneratorService generatorService = generator.getIfAvailable();
        return crudService == null || generatorService == null
                ? null
                : new CreateWorkflowToolCallback(crudService, generatorService);
    }
}
