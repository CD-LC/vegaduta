package com.agenticai.core.knowledge.connector;

/**
 * One fetched document from a live connector. The sync path turns each of
 * these into a {@code knowledge_document} row + chunks, keyed for re-sync by
 * {@code externalRef}.
 *
 * @param externalRef the provider's stable id for this document (Notion page
 *                    id, Drive file id, Confluence content id, the URL
 *                    itself for web crawl) - unique per connector
 * @param title       human-readable title (stored as the document filename)
 * @param sourceUrl   canonical link for citations - never null in practice;
 *                    a document without one still syncs, it just can't be
 *                    cited
 * @param filename    synthetic filename routed through the same extension-
 *                    based extraction path uploads use ({@code .md} stays
 *                    in-process, {@code .html} goes to the sidecar)
 * @param content     raw bytes for {@code KnowledgeIngestionService#ingestFile}
 */
public record ConnectorDocument(
    String externalRef,
    String title,
    String sourceUrl,
    String filename,
    byte[] content
) {}
