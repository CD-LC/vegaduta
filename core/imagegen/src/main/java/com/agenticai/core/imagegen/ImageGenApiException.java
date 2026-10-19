package com.agenticai.core.imagegen;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown when the outbound call to the image-generation provider (OpenAI's Images API today) fails
 * - mirrors {@code SarvamApiException}: a server-side "our call to a third party failed" condition,
 * mapped by {@code GlobalExceptionHandler} to 502 BAD_GATEWAY and logged with {@code
 * log.warn(message, exception)}.
 */
public class ImageGenApiException extends RuntimeException implements HttpStatusMapped {

    public ImageGenApiException(String message) {
        super(message);
    }

    public ImageGenApiException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 502: an outbound call to the third-party API failed. Answered by the central exception
     * handler.
     */
    @Override
    public int httpStatus() {
        return 502;
    }

    @Override
    public boolean logAtWarn() {
        return true;
    }
}
