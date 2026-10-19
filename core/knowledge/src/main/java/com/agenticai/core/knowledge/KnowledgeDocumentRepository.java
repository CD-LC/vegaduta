package com.agenticai.core.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {

    /** The file table's real query - newest upload first. */
    List<KnowledgeDocument> findByCollectionIdOrderByCreatedAtDesc(UUID collectionId);

    /** Same query bounded by {@code page} (the file-table endpoint's LIMIT). */
    List<KnowledgeDocument> findByCollectionIdOrderByCreatedAtDescIdDesc(
            UUID collectionId, Pageable page);

    /**
     * Real file count for a collection - every row regardless of status, so an in-flight or failed
     * upload still counts as "a file in this collection" (matches what the file table itself would
     * show).
     */
    long countByCollectionId(UUID collectionId);

    /**
     * Real chunk count for a collection: sums {@code chunk_count} only across READY documents
     * (in-flight/failed rows have no final count yet) - see {@link KnowledgeDocumentStatus}'s
     * javadoc. Returns null (not 0) when there are no READY documents, handled by the caller.
     */
    @Query(
            "SELECT SUM(d.chunkCount) FROM KnowledgeDocument d WHERE d.collectionId = :collectionId"
                    + " AND d.status = 'READY'")
    Long sumChunkCountForReadyDocuments(@Param("collectionId") UUID collectionId);

    /**
     * RAG audit fix (2026-07-29) - the dedup lookup behind {@code
     * KnowledgeDocumentService#requireNoDuplicate}: every document in this collection whose stored
     * SHA-256 matches the incoming upload's. A list (not {@code findFirst...}) because the caller
     * filters by status itself: FAILED rows don't block a retry, everything else does. Backed by
     * migration V154's {@code idx_knowledge_document_content_hash}.
     */
    List<KnowledgeDocument> findByCollectionIdAndContentHash(UUID collectionId, String contentHash);

    /**
     * Re-sync dedup lookup for live connectors (V254): the document a connector previously produced
     * for this provider-side id, if any - a re-sync replaces that row's chunks rather than
     * duplicating them.
     */
    java.util.Optional<KnowledgeDocument> findByConnectorIdAndExternalRef(
            UUID connectorId, String externalRef);
}
