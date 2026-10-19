package com.agenticai.core.documentgen;

/** Visitor-facing reason a document generation request was rejected before any generator ran - never a raw library error. Mirrors {@code com.agenticai.core.imagegen.VideoGenerationQuotaExceededException}. */
public class DocumentGenerationQuotaExceededException extends RuntimeException {

    /** Why the request was refused; decides the HTTP status the controller answers with. */
    public enum Reason {
        /** The plan does not include document generation (FREE): 403. */
        PLAN,
        /** The feature is switched off platform-wide: 503. */
        DISABLED,
        /** A daily/monthly cap is used up: 429. */
        LIMIT
    }

    private final Reason reason;

    /** A used-up cap (429), the common case. */
    public DocumentGenerationQuotaExceededException(String message) {
        this(Reason.LIMIT, message);
    }

    public DocumentGenerationQuotaExceededException(Reason reason, String message) {
        super(message);
        this.reason = reason == null ? Reason.LIMIT : reason;
    }

    public Reason getReason() {
        return reason;
    }
}
