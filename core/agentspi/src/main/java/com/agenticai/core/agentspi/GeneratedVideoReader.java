package com.agenticai.core.agentspi;

import java.io.InputStream;
import java.util.UUID;

/**
 * Reads a finished generated video (the result of a {@code generate_video} job) for another tool to
 * use, e.g. publishing it. Kernel-owned so a workspace tool never depends on the video vendor
 * directly (ADR-0011 rules a and b). The implementation is {@code
 * com.agenticai.core.imagegen.agenttools.ImageGenGeneratedVideoReader}.
 */
public interface GeneratedVideoReader {

    /** False when no video generation backend is wired in this deployment. */
    boolean isAvailable();

    /**
     * Opens the result of {@code jobId}; the caller closes {@link VideoFile#stream()}.
     *
     * @throws NotReadyException when the job has not completed (or has no result)
     */
    VideoFile open(UUID jobId);

    /**
     * @param contentType may be null (callers default to {@code video/mp4})
     */
    record VideoFile(InputStream stream, String contentType, long contentLength) {}

    /**
     * The job exists but its video is not ready (still generating, failed, or no stored result).
     */
    class NotReadyException extends RuntimeException {
        public NotReadyException(String message) {
            super(message);
        }
    }
}
