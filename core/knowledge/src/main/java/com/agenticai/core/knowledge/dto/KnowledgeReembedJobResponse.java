package com.agenticai.core.knowledge.dto;

import com.agenticai.core.knowledge.KnowledgeReembedJob;
import com.agenticai.core.knowledge.KnowledgeReembedJobStatus;
import java.time.Instant;
import java.util.UUID;

public record KnowledgeReembedJobResponse(
    UUID id,
    UUID collectionId,
    String sourceModel,
    String targetModel,
    KnowledgeReembedJobStatus status,
    long totalChunks,
    long processedChunks,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt
) {
    public static KnowledgeReembedJobResponse from(KnowledgeReembedJob job) {
        return new KnowledgeReembedJobResponse(
            job.getId(),
            job.getCollectionId(),
            job.getSourceModel(),
            job.getTargetModel(),
            job.getStatus(),
            job.getTotalChunks(),
            job.getProcessedChunks(),
            job.getErrorMessage(),
            job.getCreatedAt(),
            job.getUpdatedAt(),
            job.getCompletedAt()
        );
    }
}
