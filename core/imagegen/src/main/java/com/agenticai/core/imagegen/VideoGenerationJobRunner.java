package com.agenticai.core.imagegen;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background half of the video job pattern - mirrors {@code KnowledgeIngestionJobRunner} exactly,
 * including the reason it must go through {@link TenantSchemaIterator} rather than querying the
 * repository directly (a {@code @Scheduled} method has no {@code TenantContext}, so an un-iterated
 * query would silently only ever see one schema).
 *
 * <p>10s poll interval - shorter than knowledge's 15s, since a visitor watching a video-generation
 * progress bar notices latency keenly, and Veo jobs are long enough (tens of seconds to minutes)
 * that a few extra polls cost nothing next to the generation time itself.
 *
 * <p>Multi-replica: each poll runs under {@link PgAdvisoryJobLock}. {@code pollOnce} is
 * status-guarded (a finished job stops matching {@code PROCESSING}), so the lock is belt-and-braces
 * rather than correctness-critical - what it buys is not double-hitting the vendor's
 * operation-status endpoint and not racing two finalize/download passes for the same completed
 * video.
 */
@ConditionalOnEditionFeature(EditionFeature.VIDEO_DEMO_MEDIA)
@Component
public class VideoGenerationJobRunner {

    private static final long FIXED_DELAY_MS = 10_000L;
    private static final long INITIAL_DELAY_MS = 10_000L;

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationJobRunner.class);

    private static final String JOB_LOCK_NAME = "video-generation-poll";

    private final TenantSchemaIterator schemaIterator;
    private final VideoGenerationJobRepository repository;
    private final VideoGenerationJobService jobService;
    private final PgAdvisoryJobLock jobLock;

    public VideoGenerationJobRunner(
            TenantSchemaIterator schemaIterator,
            VideoGenerationJobRepository repository,
            VideoGenerationJobService jobService,
            PgAdvisoryJobLock jobLock) {
        this.schemaIterator = schemaIterator;
        this.repository = repository;
        this.jobService = jobService;
        this.jobLock = jobLock;
    }

    @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
    public void pollProcessingJobs() {
        // Lock-not-acquired is only DEBUG-logged (by the lock service itself) -
        // another replica polling is success, not an incident.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::pollProcessingJobsExclusively);
    }

    private void pollProcessingJobsExclusively() {
        schemaIterator.forEachActiveTenantSchema(
                schema -> {
                    List<VideoGenerationJob> processing =
                            repository.findByStatus(VideoGenerationJobStatus.PROCESSING);
                    for (VideoGenerationJob job : processing) {
                        try {
                            jobService.pollOnce(job.getId());
                        } catch (RuntimeException e) {
                            log.error(
                                    "Unexpected error polling video generation job {} in schema '{}'",
                                    job.getId(),
                                    schema,
                                    e);
                        }
                    }
                });
    }
}
