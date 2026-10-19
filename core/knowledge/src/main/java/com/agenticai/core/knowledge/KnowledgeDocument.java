package com.agenticai.core.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The real, persisted "one row per uploaded file" record the AXON Knowledge
 * screen's file table and ingestion-pipeline status are built on (see
 * migration V45's header comment for why this didn't exist before). Unlike
 * {@code knowledge_chunks} (raw JDBC - pgvector has no Hibernate mapping),
 * this entity has no vector column, so it's Hibernate-mapped like {@link
 * KnowledgeCollection} and {@link KnowledgeIngestionJob} - schema routing
 * comes for free from {@code TenantSchemaResolver}.
 *
 * <p>Created for every upload through both {@code KnowledgeController.upload}
 * (sync: pdf/docx/html/md/image) and {@code KnowledgeController.uploadAudio}
 * (async: audio/video, via {@link KnowledgeIngestionJob#getDocumentId()}) -
 * this is the single file-inventory table both paths write into, so the
 * frontend never has to know which path a given file went through.
 */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(name = "file_extension", length = 16)
    private String fileExtension;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeDocumentStatus status;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // Stage 5 (KB multi-modal storage, migration V66) - the object-storage
    // key (and its mime type) for the ORIGINAL uploaded file, alongside
    // whatever text got extracted from it into knowledge_chunks. Nullable:
    // rows created before this migration have no original file to offer
    // (same precedent as sarvam_generated_audio.object_key in V65), and
    // even post-migration a row stays null until KnowledgeController's
    // upload paths successfully call FileStorageService.put() - extraction
    // failure shouldn't be blocked on a storage failure or vice versa.
    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "content_type", length = 128)
    private String contentType;

    // RAG audit fix (2026-07-29, migration V154) - SHA-256 (hex) of the
    // uploaded file's raw bytes, the dedup key KnowledgeDocumentService
    // #requireNoDuplicate checks before either upload path creates a new
    // row. Nullable: rows created before V154 have no hash (and never
    // match a dedup lookup - they keep behaving exactly as before), and
    // the raw-text /ingest path has no file to hash.
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    // Live connectors (P2 product-completeness, migration V254) - the
    // citation link + re-sync key for connector-sourced documents. All three
    // nullable: plain uploads (and every pre-V254 row) carry none of them.
    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "external_ref", length = 1024)
    private String externalRef;

    @Column(name = "connector_id")
    private UUID connectorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeDocument() {
        // JPA
    }

    public KnowledgeDocument(
        UUID collectionId, String filename, String fileExtension, long fileSizeBytes, KnowledgeDocumentStatus initialStatus,
        String contentHash
    ) {
        this.collectionId = collectionId;
        this.filename = filename;
        this.fileExtension = fileExtension;
        this.fileSizeBytes = fileSizeBytes;
        this.status = initialStatus;
        this.contentHash = contentHash;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markExtracting() {
        this.status = KnowledgeDocumentStatus.EXTRACTING;
        this.updatedAt = Instant.now();
    }

    public void markEmbedding() {
        this.status = KnowledgeDocumentStatus.EMBEDDING;
        this.updatedAt = Instant.now();
    }

    public void markReady(int chunkCount) {
        this.status = KnowledgeDocumentStatus.READY;
        this.chunkCount = chunkCount;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = KnowledgeDocumentStatus.FAILED;
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }

    /**
     * Records where the original uploaded file landed in object storage.
     * Called independently of the extraction-status transitions above -
     * a document can be READY (text extracted fine) with no original file
     * (storage.put failed, or storage isn't configured) or vice versa isn't
     * really possible since this is called before extraction starts, but
     * either way the two facts (extracted-ok, original-file-kept) are
     * intentionally decoupled rather than one blocking the other.
     */
    public void attachOriginalFile(String objectKey, String contentType) {
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.updatedAt = Instant.now();
    }

    /**
     * Stamps the connector provenance (V254) - the canonical link a search
     * citation points back to ({@code sourceUrl}), the provider's stable doc
     * id used for re-sync dedup ({@code externalRef}), and the connector
     * that produced the row.
     */
    public void attachConnectorSource(String sourceUrl, String externalRef, UUID connectorId) {
        this.sourceUrl = sourceUrl;
        this.externalRef = externalRef;
        this.connectorId = connectorId;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCollectionId() {
        return collectionId;
    }

    public String getFilename() {
        return filename;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public KnowledgeDocumentStatus getStatus() {
        return status;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public UUID getConnectorId() {
        return connectorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
