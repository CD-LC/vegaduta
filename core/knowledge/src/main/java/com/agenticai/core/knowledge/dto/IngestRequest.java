package com.agenticai.core.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record IngestRequest(
    String source,
    @NotBlank String text,
    Integer chunkSize,
    Integer chunkOverlap,
    // Knowledge Stage F1 - null means the tenant's default collection (see
    // KnowledgeCollectionService.getDefaultCollectionId).
    UUID collectionId
) {
}
