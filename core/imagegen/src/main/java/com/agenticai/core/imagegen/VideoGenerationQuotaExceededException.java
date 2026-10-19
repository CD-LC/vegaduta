package com.agenticai.core.imagegen;

/** Visitor-facing reason a video generation request was rejected before any provider call was made - never a raw provider error. */
public class VideoGenerationQuotaExceededException extends RuntimeException {

    public VideoGenerationQuotaExceededException(String message) {
        super(message);
    }
}
