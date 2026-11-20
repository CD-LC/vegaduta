package com.agenticai.core.workflow;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read facade over {@link WorkflowDefinitionRepository} for the unauthenticated webhook entry
 * points, so {@link WorkflowWebhookController} never touches the repository or the {@link
 * WorkflowDefinition} entity. No tenant access guard here on purpose: the webhook controller
 * establishes the tenant context by hand before calling.
 */
@Service
public class WorkflowWebhookTargetService {

    /** The slice of a workflow definition the webhook controller needs. */
    public record Target(UUID id, WorkflowStatus status) {}

    private final WorkflowDefinitionRepository definitionRepository;

    public WorkflowWebhookTargetService(WorkflowDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Target> find(UUID workflowId) {
        return definitionRepository
                .findById(workflowId)
                .map(d -> new Target(d.getId(), d.getStatus()));
    }

    /**
     * Whether {@code nodeId} of the workflow is a hosted-form trigger node (see {@link
     * WorkflowFormService}).
     */
    @Transactional(readOnly = true)
    public boolean isFormTriggerNode(
            WorkflowFormService formService, UUID workflowId, String nodeId) {
        return definitionRepository
                .findById(workflowId)
                .map(d -> formService.isFormTriggerNode(d, nodeId))
                .orElse(false);
    }
}
