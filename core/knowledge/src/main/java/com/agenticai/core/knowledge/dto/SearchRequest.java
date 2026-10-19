package com.agenticai.core.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record SearchRequest(
    @NotBlank String query,
    @Min(1) @Max(100) Integer topK,
    // Knowledge Stage F1 - null/empty means every collection (unchanged default).
    Set<UUID> collectionIds,
    // Optional jsonb equality filter on knowledge_chunks.metadata - see
    // KnowledgeMetadataFilter for key rules (dotted keys address nested
    // objects) and allowed value types. Null/empty = no filter.
    Map<String, Object> metadataFilter
) {
    public SearchRequest(String query, Integer topK, Set<UUID> collectionIds) {
        this(query, topK, collectionIds, null);
    }
}
