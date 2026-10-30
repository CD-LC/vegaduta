package com.agenticai.core.imagegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.imagegen.dto.VideoGenerationJobResponse;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link VideoGenerationJob}'s async lifecycle (QUEUED -> PROCESSING -> COMPLETE | FAILED,
 * stamping {@code updatedAt} each step) and {@link VideoGenerationJobResponse#from}'s rule that a
 * {@code videoUrl} only exists once the job is COMPLETE.
 */
class VideoGenerationJobTest {

    @Test
    void aNewJobStartsQueuedWithTimestamps() {
        VideoGenerationJob job =
                new VideoGenerationJob("a kite over the beach", "veo-3", 8, "GOOGLE_VERTEX");

        assertThat(job.getStatus()).isEqualTo(VideoGenerationJobStatus.QUEUED);
        assertThat(job.getPrompt()).isEqualTo("a kite over the beach");
        assertThat(job.getModel()).isEqualTo("veo-3");
        assertThat(job.getDurationSeconds()).isEqualTo(8);
        assertThat(job.getProviderName()).isEqualTo("GOOGLE_VERTEX");
        assertThat(job.getProviderOperationId()).isNull();
        assertThat(job.getResultObjectKey()).isNull();
        assertThat(job.getResultContentType()).isNull();
        assertThat(job.getErrorMessage()).isNull();
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getUpdatedAt()).isEqualTo(job.getCreatedAt());
    }

    @Test
    void theFullLifecycleStampsEachTransition() {
        VideoGenerationJob job = new VideoGenerationJob("p", "veo-3", 4, "GOOGLE_VERTEX");

        job.markProcessing("operations/abc123");
        assertThat(job.getStatus()).isEqualTo(VideoGenerationJobStatus.PROCESSING);
        assertThat(job.getProviderOperationId()).isEqualTo("operations/abc123");

        job.markComplete("videos/result.mp4", "video/mp4");
        assertThat(job.getStatus()).isEqualTo(VideoGenerationJobStatus.COMPLETE);
        assertThat(job.getResultObjectKey()).isEqualTo("videos/result.mp4");
        assertThat(job.getResultContentType()).isEqualTo("video/mp4");
    }

    @Test
    void markFailedRecordsTheReason() {
        VideoGenerationJob job = new VideoGenerationJob("p", "veo-3", 4, "GOOGLE_VERTEX");

        job.markFailed("provider rejected the prompt");

        assertThat(job.getStatus()).isEqualTo(VideoGenerationJobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("provider rejected the prompt");
    }

    @Test
    void responseOnlyExposesAVideoUrlOnceComplete() {
        VideoGenerationJob job = new VideoGenerationJob("p", "veo-3", 4, "GOOGLE_VERTEX");

        // Queued: nothing to play back yet.
        assertThat(VideoGenerationJobResponse.from(job).videoUrl()).isNull();
        assertThat(VideoGenerationJobResponse.from(job).status())
                .isEqualTo(VideoGenerationJobStatus.QUEUED);

        job.markFailed("nope");
        assertThat(VideoGenerationJobResponse.from(job).videoUrl()).isNull();
        assertThat(VideoGenerationJobResponse.from(job).errorMessage()).isEqualTo("nope");

        VideoGenerationJob done = new VideoGenerationJob("p", "veo-3", 4, "GOOGLE_VERTEX");
        done.markProcessing("op");
        done.markComplete("videos/x.mp4", "video/mp4");
        VideoGenerationJobResponse r = VideoGenerationJobResponse.from(done);
        assertThat(r.videoUrl()).isEqualTo("/api/videogen/videos/" + done.getId());
        assertThat(r.status()).isEqualTo(VideoGenerationJobStatus.COMPLETE);
        assertThat(r.durationSeconds()).isEqualTo(4);
    }

    @Test
    void jobStatusHasTheFourLifecycleValues() {
        assertThat(VideoGenerationJobStatus.values())
                .containsExactly(
                        VideoGenerationJobStatus.QUEUED,
                        VideoGenerationJobStatus.PROCESSING,
                        VideoGenerationJobStatus.COMPLETE,
                        VideoGenerationJobStatus.FAILED);
    }
}
