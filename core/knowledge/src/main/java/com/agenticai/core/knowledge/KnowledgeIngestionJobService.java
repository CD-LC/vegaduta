package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.IngestResponse;
import com.agenticai.core.knowledge.dto.KnowledgeIngestionJobResponse;
import com.agenticai.core.storage.FileStorageService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge Stage F3c - CRUD/enqueue for {@link KnowledgeIngestionJob} plus {@link #processJob},
 * the actual "do the slow work" method {@code KnowledgeIngestionJobRunner} calls.
 *
 * <p><b>{@link #processJob} is deliberately NOT {@code @Transactional} as a whole</b> - same
 * reasoning {@code HumanApprovalGateService} and {@code ApprovalRequestRecorder} already documented
 * for the approval-gate flow: holding a database transaction open across a slow outbound network
 * call (here, the sidecar's {@code /transcribe}, which can legitimately take minutes) is a
 * well-known anti-pattern - it ties up a pooled connection for the duration of that call and risks
 * a transaction timeout. Each job-row write below is its own single atomic statement (see {@link
 * KnowledgeIngestionJobClaimer}), which is all the atomicity this method actually needs.
 */
@Service
@Profile("knowledge")
public class KnowledgeIngestionJobService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionJobService.class);

    private final KnowledgeIngestionJobRepository repository;
    private final SidecarClient sidecarClient;
    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeDocumentService documentService;
    private final FileStorageService fileStorageService;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeIngestionJobClaimer claimer;

    public KnowledgeIngestionJobService(
            KnowledgeIngestionJobRepository repository,
            SidecarClient sidecarClient,
            KnowledgeIngestionService ingestionService,
            KnowledgeDocumentService documentService,
            FileStorageService fileStorageService,
            JdbcTemplate jdbcTemplate,
            KnowledgeIngestionJobClaimer claimer) {
        this.repository = repository;
        this.sidecarClient = sidecarClient;
        this.ingestionService = ingestionService;
        this.documentService = documentService;
        this.fileStorageService = fileStorageService;
        this.jdbcTemplate = jdbcTemplate;
        this.claimer = claimer;
    }

    /**
     * "Build the backend to make it real" - creates the same {@link KnowledgeDocument} row the sync
     * upload path creates (status QUEUED, since this file genuinely waits for {@code
     * KnowledgeIngestionJobRunner}'s next poll rather than being processed immediately), and links
     * it to the job via {@code documentId} so {@link #processJob} can update the one row both paths
     * share - see {@code KnowledgeIngestionJob.getDocumentId()}'s javadoc.
     *
     * <p>Stage 5 (KB multi-modal storage) - also stores the original audio/ video bytes in object
     * storage right here (not in {@link #processJob}), since the full content is already in hand
     * and there's no reason to wait for the job runner's next poll just to keep the original file -
     * same best-effort, non-blocking reasoning as {@code KnowledgeController.storeOriginalFile}'s
     * javadoc (a storage failure logs a warning and the transcription job proceeds unaffected).
     */
    @Transactional
    public KnowledgeIngestionJobResponse enqueue(
            String filename,
            byte[] audioContent,
            String contentType,
            int chunkSize,
            int chunkOverlap,
            UUID collectionId,
            String contentHash) {
        KnowledgeDocument document =
                documentService.create(
                        collectionId,
                        filename,
                        audioContent.length,
                        KnowledgeDocumentStatus.QUEUED,
                        contentHash);
        storeOriginalFile(document, filename, audioContent, contentType);
        KnowledgeIngestionJob job =
                new KnowledgeIngestionJob(
                        filename,
                        collectionId,
                        chunkSize,
                        chunkOverlap,
                        audioContent,
                        document.getId());
        return KnowledgeIngestionJobResponse.from(repository.save(job));
    }

    private void storeOriginalFile(
            KnowledgeDocument document, String filename, byte[] content, String contentType) {
        String objectKey = "knowledge-documents/" + document.getId() + "/" + filename;
        String resolvedContentType = contentType != null ? contentType : "application/octet-stream";
        try {
            fileStorageService.put(objectKey, content, resolvedContentType);
            documentService.attachOriginalFile(document.getId(), objectKey, resolvedContentType);
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to store original file for knowledge document {} ({}) - transcription"
                            + " will proceed without it: {}",
                    document.getId(),
                    filename,
                    e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public KnowledgeIngestionJobResponse get(UUID jobId) {
        return KnowledgeIngestionJobResponse.from(getEntity(jobId));
    }

    /**
     * Advisory-lock entry point: takes the job from {@code QUEUED} to {@code PROCESSING} with a
     * conditional update, then runs it. If the row is no longer {@code QUEUED} (a replica running
     * per-job claiming took it, or another run did) nothing happens, so a replica without claiming
     * enabled cannot double-run a job that a claiming replica owns.
     *
     * <p>Runs one job end to end: transcribe, then chunk+embed+store via the exact same {@link
     * KnowledgeIngestionService#ingest} every other format uses, then mark complete/failed. Called
     * by {@code KnowledgeIngestionJobRunner} on a thread where {@code TenantContext} has already
     * been set correctly by {@code TenantSchemaIterator} - this method makes no tenant-context
     * decisions itself, it just does the work, the same separation of concerns {@code
     * RetryFailedActionsJob} / {@code HumanApprovalGateService.retryExecution} already established.
     *
     * <p>No retry on failure - see {@code KnowledgeIngestionJob}'s javadoc for why that's a stated
     * scope limit, not an oversight.
     */
    public void processJob(UUID jobId) {
        if (!claimer.startIfQueued(jobId)) {
            log.debug("Knowledge ingestion job {} is no longer QUEUED; skipping", jobId);
            return;
        }
        run(jobId, 0);
    }

    /**
     * The job row is written only by fenced SQL (database clock, and only while this attempt still
     * owns it), never by a full-entity save, so a slow earlier attempt cannot regress a terminal
     * state a later attempt wrote and the JVM clock never lands in {@code updated_at}.
     */
    private void run(UUID jobId, int attempt) {
        KnowledgeIngestionJob job = getEntity(jobId);
        // Transcription is real "bytes to text" work, same as sidecar
        // /extract for the sync formats - EXTRACTING is the honest shared
        // label for both (see KnowledgeDocumentStatus's javadoc).
        if (job.getDocumentId() != null) {
            documentService.markExtracting(job.getDocumentId());
        }

        try {
            String text = sidecarClient.transcribeAudio(job.getFilename(), job.getAudioContent());
            IngestResponse result =
                    ingestionService.ingest(
                            job.getFilename(),
                            text,
                            job.getChunkSize(),
                            job.getChunkOverlap(),
                            job.getCollectionId(),
                            job.getDocumentId());
            if (!claimer.complete(jobId, attempt, result.chunkCount())) {
                log.warn(
                        "Knowledge ingestion job {} attempt {} finished but no longer owns the job;"
                                + " result discarded",
                        jobId,
                        attempt);
            }
        } catch (RuntimeException e) {
            log.warn("Knowledge ingestion job {} failed: {}", jobId, e.getMessage());
            if (claimer.fail(jobId, attempt, e.getMessage())) {
                if (job.getDocumentId() != null) {
                    documentService.markFailed(job.getDocumentId(), e.getMessage());
                }
            } else {
                log.warn(
                        "Knowledge ingestion job {} attempt {} failed but no longer owns the job",
                        jobId,
                        attempt);
            }
        }
    }

    /**
     * Runs a job that {@link KnowledgeIngestionJobClaimer} already moved to {@code PROCESSING}. A
     * reclaimed (dead) claim may have written chunks before dying, so those are purged first; the
     * retry then produces the same result as a first run instead of duplicates. A claim that hit
     * the attempt cap is already {@code FAILED} in the job table; only its document is marked
     * failed here.
     */
    public void processClaimedJob(KnowledgeIngestionJobClaimer.Claim claim) {
        if (claim.poisoned()) {
            log.error(
                    "Knowledge ingestion job {} failed permanently after {} attempts",
                    claim.jobId(),
                    claim.attempt());
            if (claim.documentId() != null) {
                documentService.markFailed(
                        claim.documentId(),
                        "Gave up after "
                                + claim.attempt()
                                + " attempts: the runner stopped heartbeating each time");
            }
            return;
        }
        if (claim.reclaimed() && claim.documentId() != null) {
            purgePartialOutput(claim.documentId());
        }
        run(claim.jobId(), claim.attempt());
    }

    private void purgePartialOutput(UUID documentId) {
        String chunks = KnowledgeSchemaSupport.qualifiedTableName();
        String parents = KnowledgeSchemaSupport.qualifiedTableName("knowledge_parent_chunk");
        jdbcTemplate.update("DELETE FROM " + chunks + " WHERE document_id = ?", documentId);
        jdbcTemplate.update("DELETE FROM " + parents + " WHERE document_id = ?", documentId);
    }

    private KnowledgeIngestionJob getEntity(UUID jobId) {
        return repository
                .findById(jobId)
                .orElseThrow(() -> new KnowledgeIngestionJobNotFoundException(jobId));
    }
}
