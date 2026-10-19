package com.agenticai.core.imagegen.agenttools;

import com.agenticai.core.agentspi.GeneratedVideoReader;
import com.agenticai.core.imagegen.ImageGenNotConfiguredException;
import com.agenticai.core.imagegen.VideoGenerationJobService;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link GeneratedVideoReader} over {@link VideoGenerationJobService}, so a workspace tool (YouTube
 * upload) can read a generated video without depending on the imagegen vendor (ADR-0011 rules a and
 * b). ObjectProvider: slim contexts without a video backend simply report {@link #isAvailable()}
 * false.
 */
@Component
public class ImageGenGeneratedVideoReader implements GeneratedVideoReader {

    private final ObjectProvider<VideoGenerationJobService> jobService;

    public ImageGenGeneratedVideoReader(ObjectProvider<VideoGenerationJobService> jobService) {
        this.jobService = jobService;
    }

    @Override
    public boolean isAvailable() {
        return jobService.getIfAvailable() != null;
    }

    @Override
    public VideoFile open(UUID jobId) {
        VideoGenerationJobService service = jobService.getIfAvailable();
        if (service == null) {
            throw new NotReadyException("video generation is not available on this deployment");
        }
        try {
            VideoGenerationJobService.ResultStream result = service.openResultStream(jobId);
            return new VideoFile(result.stream(), result.contentType(), result.contentLength());
        } catch (ImageGenNotConfiguredException e) {
            throw new NotReadyException(e.getMessage());
        }
    }
}
