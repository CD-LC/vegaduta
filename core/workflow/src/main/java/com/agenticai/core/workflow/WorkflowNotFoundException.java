package com.agenticai.core.workflow;

import java.util.UUID;

public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(UUID id) {
        super("Workflow " + id + " not found");
    }
}
