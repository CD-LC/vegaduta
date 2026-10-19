package com.agenticai.core.workflow.voicemenu;

/** A DTMF collection arrived for a request/run in the wrong state - mirrors {@code WorkflowInputRequestStateException}. */
public class WorkflowVoiceMenuRequestStateException extends RuntimeException {
    public WorkflowVoiceMenuRequestStateException(String message) {
        super(message);
    }
}
