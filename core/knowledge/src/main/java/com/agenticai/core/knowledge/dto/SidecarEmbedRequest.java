package com.agenticai.core.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for the sidecar's POST /embed - field names match its
 * Pydantic model exactly. {@code model} is one of EmbeddingModelCatalog's
 * keys (multi-model ingestion) - null means "the sidecar's own default",
 * kept optional here rather than required so any existing direct caller of
 * this record (there are none left after multi-model ingestion, but the
 * null-safety costs nothing) doesn't have to know about model selection.
 */
public record SidecarEmbedRequest(
    String text,
    @JsonProperty("chunk_size") int chunkSize,
    @JsonProperty("chunk_overlap") int chunkOverlap,
    String model
) {
}
