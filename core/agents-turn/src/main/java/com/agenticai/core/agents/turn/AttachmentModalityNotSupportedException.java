package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.modality.ModelModalitiesService;

/**
 * An inline chat attachment's MIME type is one this agent's model can't
 * accept as native chat input (per {@link ModelModalitiesService}). The
 * message always names the model and what it DOES support, so the caller can
 * act on it. Mapped to HTTP 415 in {@code GlobalExceptionHandler}.
 */
public class AttachmentModalityNotSupportedException extends RuntimeException {

    public AttachmentModalityNotSupportedException(String message) {
        super(message);
    }
}
