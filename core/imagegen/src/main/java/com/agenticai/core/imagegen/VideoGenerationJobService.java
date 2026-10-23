package com.agenticai.core.imagegen;

import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderNotActiveException;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Owns the full lifecycle of an authenticated video-generation job: enqueue (quota-checked,
 * credential-resolved), and the poll step {@link VideoGenerationJobRunner} calls on a schedule.
 * Same split as {@code KnowledgeIngestionJobService}/{@code KnowledgeIngestionJobRunner} - this
 * class knows how to run one job, the runner finds which ones need running.
 *
 * <p>Credential resolution prefers the tenant's own BYOK {@code GOOGLE_VERTEX} activation if
 * present, and falls back to the platform's pool ({@link
 * VertexVeoVideoGenerationClient#submitFromPool}) otherwise - for the PAID tenants {@link
 * VideoGenerationQuotaService} admits. FREE tenants never reach either branch: server-side video
 * generation is paid-only (product decision, 2026-08-04 - {@code
 * VideoGenerationQuotaService.checkAllowed} rejects the FREE plan outright, before the enqueue
 * spends anything; the free tier's video story is the client-side on-device ladder).
 */
@ConditionalOnEditionFeature(EditionFeature.VIDEO_DEMO_MEDIA)
@Service
public class VideoGenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationJobService.class);
    private static final String OBJECT_KEY_PREFIX = "generated-videos/";

    private final VideoGenerationJobRepository jobRepository;
    private final VideoGenerationQuotaService quotaService;
    private final ProviderCredentialResolver credentialResolver;
    private final VertexVeoVideoGenerationClient veoClient;
    private final FileStorageService fileStorageService;
    private final TenantLimits tenantLimits;
    private final String defaultModel;

    public VideoGenerationJobService(
            VideoGenerationJobRepository jobRepository,
            VideoGenerationQuotaService quotaService,
            ProviderCredentialResolver credentialResolver,
            VertexVeoVideoGenerationClient veoClient,
            FileStorageService fileStorageService,
            TenantLimits tenantLimits,
            PlatformSettingsService platformSettings,
            @Value("${app.video.generation.model:veo-3.0-fast-generate-001}")
                    String defaultModelFallback) {
        this.jobRepository = jobRepository;
        this.quotaService = quotaService;
        this.credentialResolver = credentialResolver;
        this.veoClient = veoClient;
        this.fileStorageService = fileStorageService;
        this.tenantLimits = tenantLimits;
        this.defaultModel =
                platformSettings.getString("video.generation.model", defaultModelFallback);
    }

    /**
     * @throws VideoGenerationQuotaExceededException before anything is spent, if this request would
     *     exceed the tenant's tier.
     */
    public VideoGenerationJob enqueue(String prompt, int durationSeconds) {
        return enqueue(prompt, durationSeconds, null);
    }

    /**
     * @param sourceImage when present, generation is image-to-video: the provider animates FROM
     *     this exact uploaded photo (see {@link VideoGenerationClient.SourceImage}) instead of
     *     generating a scene from the prompt alone.
     * @throws VideoGenerationQuotaExceededException before anything is spent, if this request would
     *     exceed the tenant's tier.
     */
    public VideoGenerationJob enqueue(
            String prompt, int durationSeconds, VideoGenerationClient.SourceImage sourceImage) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        quotaService.checkAllowed(plan, durationSeconds);

        Optional<String> byokKey = resolveByokVertexKey();
        String providerLabel = byokKey.isPresent() ? "GOOGLE_VERTEX_BYOK" : "GOOGLE_VERTEX_POOL";
        VideoGenerationJob job =
                new VideoGenerationJob(prompt, defaultModel, durationSeconds, providerLabel);
        job = jobRepository.saveAndFlush(job);

        try {
            String operationId =
                    byokKey.isPresent()
                            ? veoClient.submit(
                                    byokKey.get(),
                                    prompt,
                                    defaultModel,
                                    durationSeconds,
                                    sourceImage)
                            : veoClient
                                    .submitFromPool(
                                            prompt, defaultModel, durationSeconds, sourceImage)
                                    .orElseThrow(
                                            () ->
                                                    new ImageGenNotConfiguredException(
                                                            "No Vertex AI video credential is configured yet."));
            job.markProcessing(operationId);
        } catch (RuntimeException e) {
            log.warn("Video generation job {} failed to start", job.getId(), e);
            job.markFailed(
                    e.getMessage() == null ? "Could not start video generation." : e.getMessage());
        }
        return jobRepository.save(job);
    }

    public VideoGenerationJob get(UUID jobId) {
        return jobRepository
                .findById(jobId)
                .orElseThrow(
                        () -> new ImageGenNotConfiguredException("No such video generation job."));
    }

    /**
     * A completed job's finished video, ready to hand to another consumer of raw video bytes -
     * added for {@code UploadVideoToYouTubeToolCallback} so that module can reuse this job's
     * storage without a new cross-module dependency on {@link FileStorageService} (which stays
     * encapsulated in this service, same as {@link VideoGenerationController}'s own `/videos/{id}`
     * route already does).
     *
     * @throws ImageGenNotConfiguredException if the job isn't {@link
     *     VideoGenerationJobStatus#COMPLETE} yet, or has no result object key (shouldn't happen for
     *     a COMPLETE job, but a stored precondition is cheaper to check here than to let a
     *     storage-layer 404 explain it badly further up)
     */
    public ResultStream openResultStream(UUID jobId) {
        VideoGenerationJob job = get(jobId);
        if (job.getStatus() != VideoGenerationJobStatus.COMPLETE
                || job.getResultObjectKey() == null) {
            throw new ImageGenNotConfiguredException(
                    "Video generation job "
                            + jobId
                            + " is not complete yet (status: "
                            + job.getStatus()
                            + ").");
        }
        String key = job.getResultObjectKey();
        long size = fileStorageService.size(key);
        return new ResultStream(fileStorageService.get(key), job.getResultContentType(), size);
    }

    /** The caller is responsible for closing {@code stream}. */
    public record ResultStream(
            java.io.InputStream stream, String contentType, long contentLength) {}

    /** Backs the Files page's "Generated video" listing, newest first. */
    public List<VideoGenerationJob> listAll() {
        return jobRepository.findAllByOrderByCreatedAtDesc();
    }

    /** One newest-first window for the paged Files-page listing. */
    public List<VideoGenerationJob> listPage(long offset, int limit) {
        return jobRepository.findAllByOrderByCreatedAtDescIdDesc(
                new OffsetLimitRequest(offset, limit));
    }

    public long countAll() {
        return jobRepository.count();
    }

    /**
     * Advances one job by one poll step. Safe to call repeatedly - a no-op once the job is COMPLETE
     * or FAILED.
     */
    public void pollOnce(UUID jobId) {
        VideoGenerationJob job = get(jobId);
        if (job.getStatus() != VideoGenerationJobStatus.PROCESSING) {
            return;
        }
        try {
            Optional<String> byokKey = resolveByokVertexKey();
            Optional<VideoGenerationClient.GeneratedVideo> result =
                    "GOOGLE_VERTEX_BYOK".equals(job.getProviderName())
                            ? veoClient.poll(
                                    byokKey.orElseThrow(),
                                    job.getProviderOperationId(),
                                    job.getModel())
                            : veoClient.pollFromPool(job.getProviderOperationId(), job.getModel());

            if (result.isEmpty()) {
                return; // still running
            }
            VideoGenerationClient.GeneratedVideo video = result.get();
            String objectKey =
                    OBJECT_KEY_PREFIX + UUID.randomUUID() + extensionFor(video.contentType());
            fileStorageService.put(objectKey, video.bytes(), video.contentType());
            job.markComplete(objectKey, video.contentType());
            jobRepository.save(job);
        } catch (RuntimeException e) {
            log.warn("Video generation job {} failed while polling", job.getId(), e);
            job.markFailed(e.getMessage() == null ? "Video generation failed." : e.getMessage());
            jobRepository.save(job);
        }
    }

    private Optional<String> resolveByokVertexKey() {
        try {
            return Optional.of(credentialResolver.resolveActiveKey(ProviderName.GOOGLE_VERTEX));
        } catch (ProviderNotActiveException e) {
            return Optional.empty();
        }
    }

    private static String extensionFor(String contentType) {
        return "video/webm".equals(contentType) ? ".webm" : ".mp4";
    }
}
