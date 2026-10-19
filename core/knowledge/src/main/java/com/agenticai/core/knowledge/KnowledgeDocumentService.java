package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.KnowledgeDocumentResponse;
import com.agenticai.core.knowledge.dto.KnowledgeStatsResponse;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.ListPaging;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Build the backend to make it real" (see migration V45's header comment) - the real per-file
 * lifecycle behind the AXON Knowledge screen's file table and ingestion-pipeline status. {@link
 * KnowledgeIngestionService} and {@link KnowledgeIngestionJobService} call the {@code markX}
 * methods here as each real pipeline stage actually happens; nothing in this class ever invents a
 * status or a count that doesn't reflect real, completed work.
 *
 * <p>RAG audit fixes (2026-07-29): this class now also owns the two ends of the document lifecycle
 * that were missing entirely - {@link #requireNoDuplicate} (content-hash dedup at upload, migration
 * V154) and {@link #delete} (the per-document erasure path: chunks + object-store original + the
 * row itself).
 */
@Service
@Profile("knowledge")
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);

    private final KnowledgeDocumentRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeIngestionJobRepository jobRepository;
    private final FileStorageService fileStorageService;

    public KnowledgeDocumentService(
            KnowledgeDocumentRepository repository,
            JdbcTemplate jdbcTemplate,
            KnowledgeIngestionJobRepository jobRepository,
            FileStorageService fileStorageService) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.jobRepository = jobRepository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Called the instant an upload is accepted, before any real processing has happened. {@code
     * contentHash} is the upload's SHA-256 from {@link #sha256Hex} (nullable only for callers with
     * no file bytes to hash - both real upload paths always pass it).
     */
    @Transactional
    public KnowledgeDocument create(
            UUID collectionId,
            String filename,
            long fileSizeBytes,
            KnowledgeDocumentStatus initialStatus,
            String contentHash) {
        String extension = extensionOf(filename);
        return repository.save(
                new KnowledgeDocument(
                        collectionId,
                        filename,
                        extension,
                        fileSizeBytes,
                        initialStatus,
                        contentHash));
    }

    /**
     * RAG audit fix (2026-07-29) - content-hash dedup at upload. Before this existed, re-uploading
     * the same file created a new {@link KnowledgeDocument} and appended a full duplicate chunk set
     * every time.
     *
     * <p><b>Chosen behavior: reject with a clear error, not silently reuse or replace.</b> Reasons:
     * (1) it matches this module's existing explicit-guard UX ({@code
     * KnowledgeCollectionService#delete}'s not-empty refusal) - the platform tells the user what's
     * blocking and what to do, it doesn't make destructive decisions for them; (2) a silent
     * "replace" would discard the existing document's chunks even when the re-upload used different
     * chunkSize/chunkOverlap settings on purpose, and a silent "reuse" would ignore those settings
     * entirely. The error names the existing document and the remedy ({@code DELETE
     * /api/knowledge/documents/{id}}, which now exists). FAILED rows never block - a failed upload
     * must always be retryable.
     *
     * <p>Thrown as {@link KnowledgeDuplicateDocumentException} (HTTP 409, problem type {@code
     * knowledge-duplicate-document}); the message names the existing file only.
     */
    @Transactional(readOnly = true)
    public void requireNoDuplicate(UUID collectionId, String contentHash) {
        repository.findByCollectionIdAndContentHash(collectionId, contentHash).stream()
                .filter(existing -> existing.getStatus() != KnowledgeDocumentStatus.FAILED)
                .findFirst()
                .ifPresent(
                        existing -> {
                            throw new KnowledgeDuplicateDocumentException(existing.getFilename());
                        });
    }

    /**
     * RAG audit fix (2026-07-29) - the per-document erasure path that didn't exist: removes the
     * document's chunks (raw, schema-qualified JDBC - same {@link KnowledgeSchemaSupport}
     * convention as every other {@code knowledge_chunks} statement, so it is inherently
     * tenant-scoped), detaches any async-job row still pointing at it (V45's FK), deletes the
     * stored original from object storage, and finally deletes the row.
     *
     * <p>The object-store delete is best-effort (logged, never rethrown), the exact posture {@code
     * TenantDataErasureService#deleteObjectBestEffort} established for the same operation at tenant
     * scope: a stuck S3 call must not leave the user unable to delete their document, and an
     * orphaned object is discoverable in the log for manual cleanup. It runs before the row delete
     * so the log line can still reference a document that demonstrably existed; the DB work is
     * transactional around it either way.
     *
     * <p>Tenant isolation needs no bespoke check: {@code repository.findById} resolves inside the
     * caller's own tenant schema (Hibernate routing), so another tenant's document id simply 404s -
     * same reasoning as {@code KnowledgeController#downloadOriginalFile}'s javadoc.
     */
    @Transactional
    public void delete(UUID documentId) {
        KnowledgeDocument document =
                repository
                        .findById(documentId)
                        .orElseThrow(() -> new KnowledgeDocumentNotFoundException(documentId));

        String table = KnowledgeSchemaSupport.qualifiedTableName();
        int chunksDeleted =
                jdbcTemplate.update("DELETE FROM " + table + " WHERE document_id = ?", documentId);
        jobRepository.detachDocument(documentId);

        if (document.getObjectKey() != null) {
            try {
                fileStorageService.delete(document.getObjectKey());
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to delete object storage key '{}' while deleting knowledge document"
                                + " {} ({}) - continuing: {}",
                        document.getObjectKey(),
                        documentId,
                        document.getFilename(),
                        e.getMessage());
            }
        }

        repository.delete(document);
        log.info(
                "Deleted knowledge document {} ({}) and {} chunk(s) from collection {}",
                documentId,
                document.getFilename(),
                chunksDeleted,
                document.getCollectionId());
    }

    @Transactional
    public void markExtracting(UUID documentId) {
        withDocument(documentId, KnowledgeDocument::markExtracting);
    }

    @Transactional
    public void markEmbedding(UUID documentId) {
        withDocument(documentId, KnowledgeDocument::markEmbedding);
    }

    @Transactional
    public void markReady(UUID documentId, int chunkCount) {
        withDocument(documentId, doc -> doc.markReady(chunkCount));
    }

    @Transactional
    public void markFailed(UUID documentId, String errorMessage) {
        withDocument(documentId, doc -> doc.markFailed(errorMessage));
    }

    /**
     * Stage 5 (KB multi-modal storage) - records where the original uploaded file landed in object
     * storage, independent of the extraction-status transitions above. Called from both upload
     * paths (sync {@code KnowledgeController.upload} and async {@code KnowledgeIngestionJobRunner})
     * right after a successful {@code FileStorageService.put()} - never blocks or fails ingestion
     * if storage itself fails, since the caller wraps the put() in its own try/catch (see each call
     * site's comment for why).
     */
    @Transactional
    public void attachOriginalFile(UUID documentId, String objectKey, String contentType) {
        withDocument(documentId, doc -> doc.attachOriginalFile(objectKey, contentType));
    }

    /**
     * Stage 5 (KB multi-modal storage) - fetches the raw entity (not the DTO) for {@code
     * KnowledgeController#downloadOriginalFile}, which needs {@link
     * KnowledgeDocument#getObjectKey()}/{@link KnowledgeDocument#getContentType()} directly rather
     * than the deliberately-redacted {@code hasOriginalFile} boolean {@link
     * com.agenticai.core.knowledge.dto.KnowledgeDocumentResponse} exposes.
     */
    @Transactional(readOnly = true)
    public KnowledgeDocument getEntity(UUID documentId) {
        return repository
                .findById(documentId)
                .orElseThrow(() -> new KnowledgeDocumentNotFoundException(documentId));
    }

    /**
     * The real file table - every upload for this collection, newest first, whatever its current
     * status. UNBOUNDED by design: internal "process every row" callers (membership checks,
     * delete-all-before-dropping-the-collection) rely on seeing all rows. The HTTP list uses {@link
     * #listByCollection(UUID, Integer)}.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> listByCollection(UUID collectionId) {
        return repository.findByCollectionIdOrderByCreatedAtDesc(collectionId).stream()
                .map(KnowledgeDocumentResponse::from)
                .toList();
    }

    /** Real total for the collection (backs the X-Total-Count header on the capped list). */
    @Transactional(readOnly = true)
    public long countByCollection(UUID collectionId) {
        return repository.countByCollectionId(collectionId);
    }

    /** Default / hard-max row counts for the file table. */
    static final int LIST_DEFAULT_LIMIT = 500;

    static final int LIST_MAX_LIMIT = 2000;

    /**
     * Newest {@code limit} documents (null = default 500, clamped to [1, 2000]), same order and
     * shape as before.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> listByCollection(UUID collectionId, Integer limit) {
        return listByCollection(collectionId, limit, null);
    }

    /**
     * As above, skipping the newest {@code offset} rows (null/negative = 0) to reach older
     * documents.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> listByCollection(
            UUID collectionId, Integer limit, Long offset) {
        var page = ListPaging.page(limit, offset, LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        return repository.findByCollectionIdOrderByCreatedAtDescIdDesc(collectionId, page).stream()
                .map(KnowledgeDocumentResponse::from)
                .toList();
    }

    /** Real file count for a collection sidebar/detail-header badge. */
    @Transactional(readOnly = true)
    public long fileCount(UUID collectionId) {
        return repository.countByCollectionId(collectionId);
    }

    /**
     * Real chunk count for a collection - sums only READY documents' final counts, see the
     * repository query's javadoc.
     */
    @Transactional(readOnly = true)
    public long chunkCount(UUID collectionId) {
        Long sum = repository.sumChunkCountForReadyDocuments(collectionId);
        return sum != null ? sum : 0L;
    }

    /**
     * Tenant-wide totals for the topbar. {@code totalChunks} and {@code totalVectors} are the same
     * real number, computed the same way - {@code knowledge_chunks} has exactly one embedding per
     * row, so "chunks" and "vectors" describe the identical count from two angles (rows of text vs.
     * rows of embeddings). Both are exposed because the design handoff labels the topbar metric
     * "vectors" while the rest of the screen talks about "chunks" - this keeps both honest rather
     * than picking one label and silently reusing it for the other's meaning.
     */
    @Transactional(readOnly = true)
    public KnowledgeStatsResponse tenantStats(long totalCollections) {
        long totalDocuments = repository.count();
        String table = KnowledgeSchemaSupport.qualifiedTableName();
        Long chunkRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        long totalChunks = chunkRows != null ? chunkRows : 0L;
        return new KnowledgeStatsResponse(
                totalCollections, totalDocuments, totalChunks, totalChunks);
    }

    /**
     * SHA-256 of the uploaded file's raw bytes, lowercase hex (64 chars) - the dedup key stored in
     * {@code knowledge_document.content_hash} (migration V154). Static so both upload controllers
     * can hash without a service round-trip; SHA-256 is guaranteed present on every JVM (the {@link
     * NoSuchAlgorithmException} branch is genuinely unreachable, wrapped only because the checked
     * exception must go somewhere).
     */
    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private void withDocument(
            UUID documentId, java.util.function.Consumer<KnowledgeDocument> mutation) {
        KnowledgeDocument document =
                repository
                        .findById(documentId)
                        .orElseThrow(() -> new KnowledgeDocumentNotFoundException(documentId));
        mutation.accept(document);
        repository.save(document);
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1
                ? filename.substring(dot).toLowerCase(java.util.Locale.ROOT)
                : null;
    }
}
