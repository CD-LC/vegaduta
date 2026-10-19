package com.agenticai.core.imagegen;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown when image generation is invoked for a tenant with no ACTIVE OpenAI credential - mirrors
 * {@code SarvamNotConfiguredException}: a client-side "you haven't set this up yet" condition,
 * mapped by {@code GlobalExceptionHandler} to 400 BAD_REQUEST, not logged as a server error.
 */
public class ImageGenNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public ImageGenNotConfiguredException(String message) {
        super(message);
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
