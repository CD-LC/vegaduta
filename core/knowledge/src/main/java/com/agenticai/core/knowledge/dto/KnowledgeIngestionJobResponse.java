package com.agenticai.core.knowledge.dto;

import com.agenticai.core.knowledge.KnowledgeIngestionJob;
import com.agenticai.core.knowledge.KnowledgeIngestionJobStatus;
import java.time.Instant;
import java.util.UUID;

public record KnowledgeIngestionJobResponse(
        UUID id,
        String filename,
        UUID collectionId,
        KnowledgeIngestionJobStatus status,
        String errorMessage,
        Integer resultChunkCount,
        Instant createdAt,
        Instant updatedAt) {
    public static KnowledgeIngestionJobResponse from(KnowledgeIngestionJob job) {
        return new KnowledgeIngestionJobResponse(
                job.getId(),
                job.getFilename(),
                job.getCollectionId(),
                job.getStatus(),
                job.getErrorMessage(),
                // Before COMPLETE the column holds the claim-attempt counter (see
                // KnowledgeIngestionJobClaimer), which is not a chunk count.
                job.getStatus() == KnowledgeIngestionJobStatus.COMPLETE
                        ? job.getResultChunkCount()
                        : null,
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
