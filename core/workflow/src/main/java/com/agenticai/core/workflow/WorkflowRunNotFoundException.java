package com.agenticai.core.workflow;

import java.util.UUID;

public class WorkflowRunNotFoundException extends RuntimeException {

    public WorkflowRunNotFoundException(UUID id) {
        super("Workflow run " + id + " not found");
    }
}
