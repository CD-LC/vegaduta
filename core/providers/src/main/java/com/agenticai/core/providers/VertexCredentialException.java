package com.agenticai.core.providers;

/** A Vertex AI service-account credential could not be loaded, or its token could not be refreshed. */
public class VertexCredentialException extends RuntimeException {

    public VertexCredentialException(String message) {
        super(message);
    }

    public VertexCredentialException(String message, Throwable cause) {
        super(message, cause);
    }
}
