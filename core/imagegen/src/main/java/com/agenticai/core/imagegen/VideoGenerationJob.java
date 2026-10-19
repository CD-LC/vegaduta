package com.agenticai.core.imagegen;

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
 * One tenant-authenticated video-generation request (migration V140) -
 * async because Veo takes tens of seconds to minutes, the same "enqueue,
 * poll {@code GET /jobs/{id}}" shape {@code KnowledgeIngestionJob} already
 * established. Unschema'd like {@code GeneratedImage} (lands in whatever
 * per-tenant schema {@code TenantContext} names) - unlike the anonymous
 * demo path's {@code DemoGeneratedVideo}, which is control-plane for the
 * capability-URL reasons that class's javadoc explains.
 */
@Entity
@Table(name = "video_generation_job")
public class VideoGenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "provider_name", nullable = false, length = 32)
    private String providerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VideoGenerationJobStatus status;

    /** The Vertex long-running-operation name while {@code PROCESSING} - opaque to everything but {@link VertexVeoVideoGenerationClient}. */
    @Column(name = "provider_operation_id", length = 512)
    private String providerOperationId;

    @Column(name = "result_object_key", length = 512)
    private String resultObjectKey;

    @Column(name = "result_content_type", length = 64)
    private String resultContentType;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VideoGenerationJob() {
        // JPA
    }

    public VideoGenerationJob(String prompt, String model, int durationSeconds, String providerName) {
        this.prompt = prompt;
        this.model = model;
        this.durationSeconds = durationSeconds;
        this.providerName = providerName;
        this.status = VideoGenerationJobStatus.QUEUED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markProcessing(String providerOperationId) {
        this.status = VideoGenerationJobStatus.PROCESSING;
        this.providerOperationId = providerOperationId;
        this.updatedAt = Instant.now();
    }

    public void markComplete(String resultObjectKey, String resultContentType) {
        this.status = VideoGenerationJobStatus.COMPLETE;
        this.resultObjectKey = resultObjectKey;
        this.resultContentType = resultContentType;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = VideoGenerationJobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getModel() {
        return model;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public String getProviderName() {
        return providerName;
    }

    public VideoGenerationJobStatus getStatus() {
        return status;
    }

    public String getProviderOperationId() {
        return providerOperationId;
    }

    public String getResultObjectKey() {
        return resultObjectKey;
    }

    public String getResultContentType() {
        return resultContentType;
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
}
