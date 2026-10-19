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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Knowledge Stage F3c - the async "upload returns immediately, poll for
 * status" unit for slow ingestion formats (audio today; video, Stage F3d,
 * will reuse this same table rather than inventing a second one). See
 * migration V17's header comment for the full rationale.
 *
 * <p><b>No retry-with-backoff here</b> - unlike Stage C's {@code
 * ApprovalRequest}/{@code RetryFailedActionsJob}, a failed transcription
 * job is NOT automatically retried; it goes straight to {@code FAILED}
 * with {@code errorMessage} set, and re-uploading is the user's recourse.
 * Stated honestly as a scope limit for this stage: {@code
 * ApprovalActionExecutor}'s retry machinery is tightly coupled to the
 * approval-gate domain (it retries executing an {@code ApprovalRequest},
 * not "redo an arbitrary async job with a downstream chunk/embed/store
 * side effect") and retrofitting it here would be a bigger, more
 * architecturally awkward lift than this stage's scope justifies. A
 * transient sidecar hiccup during transcription fails the job outright
 * today, same as any other single-attempt operation in this codebase
 * before Stage C existed.
 */
@Entity
@Table(name = "knowledge_ingestion_job")
public class KnowledgeIngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(name = "chunk_overlap", nullable = false)
    private int chunkOverlap;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeIngestionJobStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "result_chunk_count")
    private Integer resultChunkCount;

    // "Build the backend to make it real" (migration V45) - links this job
    // to the KnowledgeDocument row created for it at enqueue time, so
    // KnowledgeIngestionJobService.processJob can update the same
    // file-inventory row the sync upload path would have created, instead
    // of the async path tracking status in a second, disconnected place.
    @Column(name = "document_id")
    private UUID documentId;

    // Stage F3d: also holds video file bytes now (the column/field name
    // predates video support and wasn't renamed - see
    // KnowledgeController.TRANSCRIBABLE_EXTENSIONS's javadoc for why a
    // rename felt like unnecessary churn here). Nulled out by
    // KnowledgeIngestionJobService once the job runner has consumed it
    // (win or lose) - see this class's javadoc and migration V17's header
    // comment on why this table is not meant to hold a backlog of large
    // unprocessed blobs for long.
    // @JdbcTypeCode(VARBINARY), not @Lob: @Lob defaults byte[] to Postgres'
    // oid (large object) type, but migration V17 declares this column plain
    // bytea - the mismatch fails Hibernate's schema validation at startup
    // ("found bytea, but expecting oid").
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "audio_content")
    private byte[] audioContent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeIngestionJob() {
        // JPA
    }

    public KnowledgeIngestionJob(
        String filename, UUID collectionId, int chunkSize, int chunkOverlap, byte[] audioContent, UUID documentId
    ) {
        this.filename = filename;
        this.collectionId = collectionId;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.audioContent = audioContent;
        this.documentId = documentId;
        this.status = KnowledgeIngestionJobStatus.QUEUED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markProcessing() {
        this.status = KnowledgeIngestionJobStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markComplete(int resultChunkCount) {
        this.status = KnowledgeIngestionJobStatus.COMPLETE;
        this.resultChunkCount = resultChunkCount;
        this.audioContent = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = KnowledgeIngestionJobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.audioContent = null;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public UUID getCollectionId() {
        return collectionId;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public KnowledgeIngestionJobStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getResultChunkCount() {
        return resultChunkCount;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public byte[] getAudioContent() {
        return audioContent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
