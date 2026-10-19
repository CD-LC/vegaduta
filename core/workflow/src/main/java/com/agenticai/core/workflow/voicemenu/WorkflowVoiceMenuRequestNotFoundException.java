package com.agenticai.core.workflow.voicemenu;

/** Mirrors {@code WorkflowInputRequestNotFoundException} for the voice-menu halt/resume pair. */
public class WorkflowVoiceMenuRequestNotFoundException extends RuntimeException {
    public WorkflowVoiceMenuRequestNotFoundException(String message) {
        super(message);
    }
}
