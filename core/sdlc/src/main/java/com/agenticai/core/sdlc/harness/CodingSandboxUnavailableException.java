package com.agenticai.core.sdlc.harness;

/** This deployment (or workspace) is not set up to run a coding task; the message is safe to show the user. */
public class CodingSandboxUnavailableException extends RuntimeException {

    public CodingSandboxUnavailableException(String message) {
        super(message);
    }
}
