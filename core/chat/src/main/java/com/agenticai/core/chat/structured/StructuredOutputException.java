package com.agenticai.core.chat.structured;

/**
 * A model reply could not be turned into the JSON the caller asked for - either no JSON value
 * could be located in it at all, or one was found but did not bind to the requested type.
 *
 * <p>Unchecked on purpose: every call site already has its own "the model answered badly"
 * handling (a fallback value, a domain exception with a user-facing message, a repair turn), and
 * a checked exception would only force a second, redundant catch around it. {@link #getMessage()}
 * is written to be pasted back into a repair prompt - it says what was wrong, never the raw
 * reply (which may be long, and is available separately via {@link #rawText()}).
 */
public class StructuredOutputException extends RuntimeException {

    private final transient String rawText;

    public StructuredOutputException(String message, String rawText) {
        super(message);
        this.rawText = rawText;
    }

    public StructuredOutputException(String message, String rawText, Throwable cause) {
        super(message, cause);
        this.rawText = rawText;
    }

    /** The model reply that failed to parse, exactly as it was handed to the parser (may be null). */
    public String rawText() {
        return rawText;
    }
}
