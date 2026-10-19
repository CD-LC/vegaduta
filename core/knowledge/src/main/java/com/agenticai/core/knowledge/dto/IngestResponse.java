package com.agenticai.core.knowledge.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of one ingestion. {@code documentId} is the knowledge-base document row the chunks belong to
 * (null for a plain text ingest that tracks no document); the console links a face enrollment to it.
 */
public record IngestResponse(int chunkCount, int dimensions, List<UUID> chunkIds, UUID documentId) {

    /** An ingest that tracks no document row. */
    public IngestResponse(int chunkCount, int dimensions, List<UUID> chunkIds) {
        this(chunkCount, dimensions, chunkIds, null);
    }
}
