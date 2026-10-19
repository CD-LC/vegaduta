package com.agenticai.core.workflow.humaninput;

/** A submit arrived for a request/run in the wrong state, or values failed schema validation - mapped to 409/400. */
public class WorkflowInputRequestStateException extends RuntimeException {

    public WorkflowInputRequestStateException(String message) {
        super(message);
    }
}
