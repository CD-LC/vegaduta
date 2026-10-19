package com.agenticai.core.agents.memory;

/** A hand-added fact was refused because the caller (or the whole store) has no room left. Message is safe to show. */
public class MemoryCapExceededException extends RuntimeException {

    public MemoryCapExceededException(String message) {
        super(message);
    }
}
