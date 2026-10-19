package com.agenticai.core.documentgen;

/** Thrown by {@code DocumentGenerationService#getStatus} when no job with the given id exists for the calling tenant. Mirrors {@code com.agenticai.core.imagegen.ImageGenNotConfiguredException}'s "client-side, not a server error" posture. */
public class DocumentGenerationJobNotFoundException extends RuntimeException {

    public DocumentGenerationJobNotFoundException(String message) {
        super(message);
    }
}
