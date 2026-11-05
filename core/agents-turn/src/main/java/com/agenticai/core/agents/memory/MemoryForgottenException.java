package com.agenticai.core.agents.memory;

/**
 * An automated writer (the agent's {@code remember_fact} tools) tried to save a fact whose exact text
 * was forgotten for this person: a tombstone for it exists. Only the person re-adding it by hand, on
 * the Memory page, brings it back. Carries no fact text.
 */
public class MemoryForgottenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The message the tools hand back to the model; it names no content. */
    public static final String TOOL_NOTE =
            "This fact was forgotten at the person's request and is not saved again unless they re-add it themselves.";

    public MemoryForgottenException() {
        super(TOOL_NOTE);
    }
}
