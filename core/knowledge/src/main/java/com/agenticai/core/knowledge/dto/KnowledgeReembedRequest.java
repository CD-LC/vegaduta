package com.agenticai.core.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/knowledge/collections/{collectionId}/reembed} - one of EmbeddingModelCatalog's keys. */
public record KnowledgeReembedRequest(@NotBlank String targetModel) {
}
