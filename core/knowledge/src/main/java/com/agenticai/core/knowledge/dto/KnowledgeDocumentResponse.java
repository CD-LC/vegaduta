package com.agenticai.core.knowledge.dto;

import com.agenticai.core.knowledge.KnowledgeDocument;
import com.agenticai.core.knowledge.KnowledgeDocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code hasOriginalFile}/{@code contentType} (Stage 5, KB multi-modal
 * storage) deliberately expose the FACT that an original file is available
 * and its mime type, not the raw {@link KnowledgeDocument#getObjectKey()}
 * itself - the frontend fetches the actual bytes through {@code
 * GET /api/knowledge/documents/{id}/file} (an authenticated, tenant-scoped
 * controller endpoint, see {@code KnowledgeController#downloadOriginalFile}),
 * never by constructing an object-storage URL client-side.
 */
public record KnowledgeDocumentResponse(
    UUID id,
    UUID collectionId,
    String filename,
    String fileExtension,
    long fileSizeBytes,
    KnowledgeDocumentStatus status,
    Integer chunkCount,
    String errorMessage,
    boolean hasOriginalFile,
    String contentType,
    String sourceUrl,
    Instant createdAt,
    Instant updatedAt
) {
    public static KnowledgeDocumentResponse from(KnowledgeDocument document) {
        return new KnowledgeDocumentResponse(
            document.getId(),
            document.getCollectionId(),
            document.getFilename(),
            document.getFileExtension(),
            document.getFileSizeBytes(),
            document.getStatus(),
            document.getChunkCount(),
            document.getErrorMessage(),
            document.getObjectKey() != null,
            document.getContentType(),
            // V254 - the connector source link (citation target); null for
            // plain uploads and pre-V254 rows.
            document.getSourceUrl(),
            document.getCreatedAt(),
            document.getUpdatedAt()
        );
    }
}
