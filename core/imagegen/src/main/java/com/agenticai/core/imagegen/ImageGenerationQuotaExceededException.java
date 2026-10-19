package com.agenticai.core.imagegen;

/** Visitor-facing reason a static image generation request was rejected before any provider call was made - never a raw provider error. */
public class ImageGenerationQuotaExceededException extends RuntimeException {

    public ImageGenerationQuotaExceededException(String message) {
        super(message);
    }
}
