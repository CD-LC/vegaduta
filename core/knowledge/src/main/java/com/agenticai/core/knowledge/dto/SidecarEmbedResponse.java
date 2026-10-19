package com.agenticai.core.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code model} is whichever EmbeddingModelCatalog key the sidecar actually
 * used (echoes the request, or its own default if none was sent).
 * {@code dimensions} is the STORED width (EmbeddingModelCatalog.STORAGE_DIMENSIONS,
 * currently 768) - every chunk's embedding array here is already zero-padded
 * to that length regardless of the model's native output size, so Java never
 * needs to know or handle per-model dimensionality itself.
 */
public record SidecarEmbedResponse(
    String model,
    int dimensions,
    @JsonProperty("chunk_size") int chunkSize,
    @JsonProperty("chunk_overlap") int chunkOverlap,
    List<SidecarChunk> chunks
) {
}
