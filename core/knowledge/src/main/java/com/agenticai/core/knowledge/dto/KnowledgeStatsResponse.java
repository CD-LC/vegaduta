package com.agenticai.core.knowledge.dto;

/**
 * Tenant-wide totals for the Knowledge screen's topbar - every field is a
 * real {@code COUNT(*)}/{@code SUM(...)}, computed live on each request (no
 * caching, no fabricated ticking counter - see KnowledgeDocumentService for
 * the exact queries).
 */
public record KnowledgeStatsResponse(long totalCollections, long totalDocuments, long totalChunks, long totalVectors) {
}
