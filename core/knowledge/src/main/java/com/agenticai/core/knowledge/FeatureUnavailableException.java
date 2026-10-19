package com.agenticai.core.knowledge;

/**
 * A capability that needs the optional Python sidecar (PDF/OCR extraction, transcription, media
 * rendering, sidecar embeddings) was asked for while the sidecar is switched off
 * ({@code app.sidecar.enabled=false}, e.g. the personal profile). It is a
 * {@link SidecarCommunicationException} so every existing fail-open caller keeps working, but is
 * answered as 503 "feature unavailable" with an actionable message instead of a bad-gateway error.
 */
public class FeatureUnavailableException extends SidecarCommunicationException {

    public FeatureUnavailableException(String message) {
        super(message, null);
    }
}
