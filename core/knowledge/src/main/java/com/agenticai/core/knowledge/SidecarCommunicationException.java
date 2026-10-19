package com.agenticai.core.knowledge;

/** Thrown when the Java core can't reach (or gets a bad response from) the Python sidecar. */
public class SidecarCommunicationException extends RuntimeException {

    public SidecarCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
