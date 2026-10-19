package com.agenticai.core.agents.turn;

/**
 * An inline chat attachment is structurally unusable: missing mimeType/data,
 * invalid base64, empty decoded payload, or an unparseable MIME string.
 * Mapped to HTTP 400 in {@code GlobalExceptionHandler}.
 */
public class MalformedAttachmentException extends RuntimeException {

    public MalformedAttachmentException(String message) {
        super(message);
    }
}
