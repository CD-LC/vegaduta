package com.agenticai.core.agents.learning;

/** An applier could not apply an approved proposal; the message is shown to the person as is. */
public class LearningProposalApplyException extends Exception {

    private static final long serialVersionUID = 1L;

    public LearningProposalApplyException(String message) {
        super(message);
    }
}
