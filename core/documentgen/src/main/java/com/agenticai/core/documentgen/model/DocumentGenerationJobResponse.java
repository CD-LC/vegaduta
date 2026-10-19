package com.agenticai.core.documentgen.model;

import com.agenticai.core.documentgen.entity.DocumentGenerationJob;
import java.time.Instant;

/**
 * API-facing view of a {@link DocumentGenerationJob} - what {@code
 * DocumentGenerationController} returns from every endpoint, and what a
 * future {@code DocumentGenerationToolCallback} turns into a
 * {@code /api/documentgen/documents/{id}} reference (same "tool result can
 * only carry a JSON reference, never raw binary" shape {@code
 * GenerateImageToolCallback} already established).
 *
 * @param id the job id (string form of the entity's UUID primary key)
 * @param status current lifecycle state
 * @param type requested output format
 * @param filename the filename WITH extension (unlike the request's extension-less {@code filename})
 * @param downloadUrl {@code "/api/documentgen/documents/" + id}, only populated once {@link #status} is {@code COMPLETE} - {@code null} otherwise
 * @param createdAt when the job was requested
 * @param completedAt when the job reached {@code COMPLETE} or {@code FAILED} (nullable - still in flight)
 * @param errorMessage set only when {@link #status} is {@code FAILED}
 */
public record DocumentGenerationJobResponse(
    String id,
    DocumentGenerationStatus status,
    DocumentType type,
    String filename,
    String downloadUrl,
    Instant createdAt,
    Instant completedAt,
    String errorMessage
) {

    public static DocumentGenerationJobResponse from(DocumentGenerationJob job) {
        String downloadUrl = job.getStatus() == DocumentGenerationStatus.COMPLETE
            ? "/api/documentgen/documents/" + job.getId()
            : null;
        return new DocumentGenerationJobResponse(
            job.getId().toString(),
            job.getStatus(),
            job.getType(),
            job.getFilename(),
            downloadUrl,
            job.getCreatedAt(),
            job.getCompletedAt(),
            job.getErrorMessage()
        );
    }
}
