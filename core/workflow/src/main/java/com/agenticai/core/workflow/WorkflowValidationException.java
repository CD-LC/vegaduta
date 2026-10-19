package com.agenticai.core.workflow;

import java.util.List;

/**
 * Thrown by {@link OrchestratorService#validate} - the governance gate every
 * save-as-ACTIVE and every run must pass through. Carries the full list of
 * problems found (not just the first one) so the canvas can highlight every
 * offending node in one round trip instead of a fix-one/resubmit/find-the-
 * next-one loop.
 */
public class WorkflowValidationException extends RuntimeException {

    private final List<String> errors;

    public WorkflowValidationException(List<String> errors) {
        super("Workflow graph failed validation: " + String.join("; ", errors));
        this.errors = errors;
    }

    public List<String> getErrors() {
        return errors;
    }
}
