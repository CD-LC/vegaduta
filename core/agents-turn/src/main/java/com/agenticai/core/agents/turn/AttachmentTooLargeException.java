package com.agenticai.core.agents.turn;

/** Thrown when a chat attachment exceeds {@link ChatAttachmentService}'s per-category size cap. */
public class AttachmentTooLargeException extends RuntimeException {

    public AttachmentTooLargeException(String message) {
        super(message);
    }
}
