package com.agenticai.core.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * {@code POST /api/knowledge/ingest-url} - fetch a public URL's document/page and ingest it into a
 * collection through the exact upload pipeline ({@link
 * com.agenticai.core.knowledge.KnowledgeUrlIngester}). {@code collectionId} null = the tenant's
 * default collection, same convention as {@link IngestRequest}.
 */
public record IngestUrlRequest(
    @NotBlank String url,
    Integer chunkSize,
    Integer chunkOverlap,
    UUID collectionId
) {
}
