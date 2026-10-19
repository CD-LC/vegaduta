package com.agenticai.core.imagegen.dto;

import com.agenticai.core.imagegen.VideoGenerationJob;
import com.agenticai.core.imagegen.VideoGenerationJobStatus;
import java.time.Instant;
import java.util.UUID;

public record VideoGenerationJobResponse(
    UUID jobId,
    VideoGenerationJobStatus status,
    String prompt,
    int durationSeconds,
    String videoUrl,
    String errorMessage,
    Instant createdAt
) {
    public static VideoGenerationJobResponse from(VideoGenerationJob job) {
        String videoUrl = job.getStatus() == VideoGenerationJobStatus.COMPLETE
            ? "/api/videogen/videos/" + job.getId()
            : null;
        return new VideoGenerationJobResponse(
            job.getId(), job.getStatus(), job.getPrompt(), job.getDurationSeconds(),
            videoUrl, job.getErrorMessage(), job.getCreatedAt()
        );
    }
}
