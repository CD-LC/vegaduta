package com.agenticai.core.workflow;

import com.agenticai.core.sharing.PermissionLevel;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Public RUNNER-level access check for code outside this package that acts on a run of a workflow,
 * such as submitting a human-input form that resumes it ({@code WorkflowInputRequestController}):
 * whoever may run the workflow may answer its forms, nobody else.
 *
 * <p>Delegates to {@link WorkflowService}'s package-private {@code requireAccess}, so the sharing
 * and legacy-row rules live in one place. It is a separate class rather than another public method
 * on {@code WorkflowService} because that file is past the file-size ratchet.
 */
@Component
public class WorkflowRunnerAccess {

    private final WorkflowService workflowService;

    public WorkflowRunnerAccess(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /** Throws {@code ResourceAccessDeniedException} unless the caller may run the workflow. */
    public WorkflowDefinition requireRunnerAccess(UUID workflowId) {
        return workflowService.requireAccess(workflowId, PermissionLevel.RUNNER);
    }
}
