package com.agenticai.core.agents.tools;

import com.agenticai.core.approval.RetryableActionFailure;

/**
 * A replay refused because a safety precondition could not be CHECKED right now (not because it
 * failed). Nothing ran; the retry job may try again once the check works.
 */
public class AgentToolReplayRetryableException extends AgentToolReplayException
        implements RetryableActionFailure {

    public AgentToolReplayRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
