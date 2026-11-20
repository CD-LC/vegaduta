package com.agenticai.core.workflow;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow-definition counts for other feature areas (keeps {@link WorkflowDefinitionRepository}
 * private).
 */
@Service
public class WorkflowDefinitionCountService {

    private final WorkflowDefinitionRepository definitionRepository;

    public WorkflowDefinitionCountService(WorkflowDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    @Transactional(readOnly = true)
    public long count() {
        return definitionRepository.count();
    }
}
