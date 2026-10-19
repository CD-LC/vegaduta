package com.agenticai.core.knowledge.dto;

import com.agenticai.core.knowledge.KnowledgeCollection;
import java.time.Instant;
import java.util.UUID;

public record KnowledgeCollectionResponse(
    UUID id,
    String name,
    String description,
    UUID parentCollectionId,
    boolean isDefault,
    String embeddingModel,
    // RAG audit fix (2026-07-29) - the collection's Postgres text-search
    // config (see TextSearchConfigCatalog); null only for rows read from a
    // tenant schema that hasn't applied migration V154 yet, which every
    // consumer treats as 'simple'.
    String textSearchConfig,
    // "Build the backend to make it real" - real counts from
    // KnowledgeDocumentService (fileCount = every knowledge_document row for
    // this collection; chunkCount = the sum of chunk_count across its READY
    // documents), not placeholders. See KnowledgeCollectionService.get/listAll
    // for how these are computed.
    long fileCount,
    long chunkCount,
    Instant createdAt,
    Instant updatedAt,
    // Migration V362 - READY or REEMBEDDING (a re-embed job is building
    // vectors for a new model; search keeps using embeddingModel until the flip).
    String embeddingStatus,
    // Migration V361 - the configured chunking budget; null = platform default.
    Integer chunkSize,
    Integer chunkOverlap
) {
    public static KnowledgeCollectionResponse from(KnowledgeCollection collection, long fileCount, long chunkCount) {
        return new KnowledgeCollectionResponse(
            collection.getId(),
            collection.getName(),
            collection.getDescription(),
            collection.getParentCollectionId(),
            collection.isDefault(),
            collection.getEmbeddingModel(),
            collection.getTextSearchConfig(),
            fileCount,
            chunkCount,
            collection.getCreatedAt(),
            collection.getUpdatedAt(),
            collection.getEmbeddingStatus(),
            collection.getChunkSize(),
            collection.getChunkOverlap()
        );
    }
}
