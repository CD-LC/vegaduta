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
 * One request to switch a knowledge collection to a different embedding model
 * (migration V362). Hibernate-mapped like {@link KnowledgeIngestionJob} - plain
 * relational data; the vectors themselves live in the raw-JDBC shadow table
 * (V363). State transitions are guarded by
 * {@link KnowledgeReembedJobStatus#canTransitionTo} so a bug elsewhere can't,
 * say, complete a job that never flipped.
 */
@Entity
@Table(name = "knowledge_reembed_job")
public class KnowledgeReembedJob {

    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    @Column(name = "source_model", nullable = false, length = 64)
    private String sourceModel;

    @Column(name = "target_model", nullable = false, length = 64)
    private String targetModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeReembedJobStatus status;

    @Column(name = "total_chunks", nullable = false)
    private long totalChunks;

    @Column(name = "processed_chunks", nullable = false)
    private long processedChunks;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected KnowledgeReembedJob() {
        // JPA
    }

    public KnowledgeReembedJob(UUID collectionId, String sourceModel, String targetModel, long totalChunks) {
        this.collectionId = collectionId;
        this.sourceModel = sourceModel;
        this.targetModel = targetModel;
        this.totalChunks = totalChunks;
        this.processedChunks = 0;
        this.status = KnowledgeReembedJobStatus.QUEUED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markEmbedding() {
        transition(KnowledgeReembedJobStatus.EMBEDDING);
    }

    public void markSweeping() {
        transition(KnowledgeReembedJobStatus.SWEEPING);
    }

    public void markCompleted() {
        transition(KnowledgeReembedJobStatus.COMPLETED);
        this.processedChunks = Math.max(this.processedChunks, this.totalChunks);
        this.completedAt = this.updatedAt;
    }

    public void markFailed(String errorMessage) {
        transition(KnowledgeReembedJobStatus.FAILED);
        this.errorMessage = errorMessage == null ? "unknown error"
            : errorMessage.length() > MAX_ERROR_LENGTH ? errorMessage.substring(0, MAX_ERROR_LENGTH) : errorMessage;
        this.completedAt = this.updatedAt;
    }

    /** Progress is recomputed from durable state (shadow rows), never incremented - see V362. */
    public void updateProgress(long processedChunks, long totalChunks) {
        this.totalChunks = Math.max(0, totalChunks);
        this.processedChunks = Math.max(0, Math.min(processedChunks, this.totalChunks));
        this.updatedAt = Instant.now();
    }

    private void transition(KnowledgeReembedJobStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Re-embed job " + id + " cannot move from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCollectionId() {
        return collectionId;
    }

    public String getSourceModel() {
        return sourceModel;
    }

    public String getTargetModel() {
        return targetModel;
    }

    public KnowledgeReembedJobStatus getStatus() {
        return status;
    }

    public long getTotalChunks() {
        return totalChunks;
    }

    public long getProcessedChunks() {
        return processedChunks;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
