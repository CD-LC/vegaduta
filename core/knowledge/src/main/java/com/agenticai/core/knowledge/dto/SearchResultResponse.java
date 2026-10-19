package com.agenticai.core.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * {@code documentId} (Stage 5, KB multi-modal storage - nullable: chunks
 * ingested before migration V66, or via the raw {@code /ingest} text
 * endpoint which has no owning document, both leave this null) resolves a
 * search hit back to the {@link com.agenticai.core.knowledge.KnowledgeDocument}
 * that owns it - {@code GET /api/knowledge/documents/{documentId}/file}
 * (see {@code KnowledgeController#downloadOriginalFile}) is how a caller
 * turns this into the actual original file, not just its extracted text.
 *
 * <p>{@code distance} (Master-Roadmap-2026.md item #2, hybrid search): no
 * longer always a literal pgvector cosine distance. {@link
 * com.agenticai.core.knowledge.KnowledgeSearchService} now fuses a vector
 * search with a keyword search via Reciprocal Rank Fusion, and optionally
 * reranks the result with a cross-encoder - in both cases this field is
 * repurposed to carry the negated fused/reranked score, so the
 * "ascending = more relevant" ordering convention this field has always had
 * still holds for every caller/consumer, even though the number itself is
 * no longer directly comparable to a raw cosine distance from before this
 * change. Nothing in this codebase parses or thresholds on the literal
 * value today - only sort order - so this is a non-breaking change for
 * every current caller (see {@code KnowledgeSearchToolCallback}, which just
 * serializes this record as-is).
 *
 * <p>Span-level citation (deep-research item): {@code startChar}/{@code endChar}
 * are offsets into {@link #content()} (this chunk, end exclusive) and {@code quote}
 * is {@code content.substring(startChar, endChar)} - the sentence that best matches
 * the query. All three are null unless {@code app.knowledge.span-citations.enabled}
 * is on, and are omitted from JSON when null, so existing consumers and payloads
 * are unchanged.
 */
public record SearchResultResponse(
    UUID id, String source, int chunkIndex, String content, UUID collectionId, double distance, UUID documentId,
    String sourceUrl,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer startChar,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer endChar,
    @JsonInclude(JsonInclude.Include.NON_NULL) String quote
) {
    /** Compatibility ctor for the pre-span shape (no span fields). */
    public SearchResultResponse(
        UUID id, String source, int chunkIndex, String content, UUID collectionId, double distance, UUID documentId,
        String sourceUrl
    ) {
        this(id, source, chunkIndex, content, collectionId, distance, documentId, sourceUrl, null, null, null);
    }

    /** Compatibility ctor for the pre-citation shape (no sourceUrl). */
    public SearchResultResponse(
        UUID id, String source, int chunkIndex, String content, UUID collectionId, double distance, UUID documentId
    ) {
        this(id, source, chunkIndex, content, collectionId, distance, documentId, null, null, null, null);
    }

    /** Copy with a different ranking value; keeps every other field including the span. */
    public SearchResultResponse withDistance(double newDistance) {
        return new SearchResultResponse(id, source, chunkIndex, content, collectionId, newDistance, documentId,
            sourceUrl, startChar, endChar, quote);
    }

    /** Copy with a resolved source URL; keeps every other field including the span. */
    public SearchResultResponse withSourceUrl(String newSourceUrl) {
        return new SearchResultResponse(id, source, chunkIndex, content, collectionId, distance, documentId,
            newSourceUrl, startChar, endChar, quote);
    }

    /** Copy with no span fields (span citations switched off); returns this when there is none to drop. */
    public SearchResultResponse withoutSpan() {
        if (startChar == null && endChar == null && quote == null) {
            return this;
        }
        return new SearchResultResponse(id, source, chunkIndex, content, collectionId, distance, documentId,
            sourceUrl, null, null, null);
    }

    /** Copy carrying a span into {@link #content()}. */
    public SearchResultResponse withSpan(int start, int end, String spanQuote) {
        return new SearchResultResponse(id, source, chunkIndex, content, collectionId, distance, documentId,
            sourceUrl, start, end, spanQuote);
    }
}
