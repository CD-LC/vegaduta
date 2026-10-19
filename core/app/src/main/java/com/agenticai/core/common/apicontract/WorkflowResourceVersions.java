package com.agenticai.core.common.apicontract;

import com.agenticai.core.security.http.ResourceVersionSource;
import com.agenticai.core.workflow.WorkflowService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Conditional-request adapter for workflows: the entity tag of {@code /api/workflows/{workflowId}}
 * is the definition's {@code updatedAt}, which {@code WorkflowDefinition#update} and every other
 * mutator move. Access is still enforced by the handler the request reaches.
 */
@Component
public class WorkflowResourceVersions implements ResourceVersionSource {

    private final WorkflowService workflows;

    public WorkflowResourceVersions(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @Override
    public String collectionPath() {
        return "/api/workflows";
    }

    @Override
    public Optional<Instant> lastModified(UUID id) {
        return workflows.definitionLastModified(id);
    }
}
