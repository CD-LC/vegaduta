package com.agenticai.core.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeIngestionJobRepository
        extends JpaRepository<KnowledgeIngestionJob, UUID> {

    /** {@code KnowledgeIngestionJobRunner}'s poll query. */
    List<KnowledgeIngestionJob> findByStatus(KnowledgeIngestionJobStatus status);

    /**
     * Oldest-first ids of jobs in {@code status}, bounded by {@code page}. Ids only on purpose: the
     * entity carries the upload's {@code audio_content} bytea, which the poll must not re-read for
     * the whole backlog every tick ({@code processJob} re-fetches the one job it runs by id). Pass
     * {@link Pageable#unpaged()} for no limit.
     */
    @Query(
            "SELECT j.id FROM KnowledgeIngestionJob j WHERE j.status = :status ORDER BY j.createdAt"
                    + " ASC")
    List<UUID> findIdsByStatusOrderByCreatedAtAsc(
            @Param("status") KnowledgeIngestionJobStatus status, Pageable page);

    /**
     * RAG audit fix (2026-07-29) - clears the {@code document_id} FK (V45) on any job row that
     * still points at a document being deleted, so {@code KnowledgeDocumentService#delete} doesn't
     * trip the constraint. The job row itself is deliberately kept (it's the durable async-upload
     * history; V17's design nulls its blob after processing anyway) - only the link to the now-gone
     * document goes. Bulk JPQL so the potentially large {@code audio_content} bytea is never loaded
     * just to null one column.
     */
    @Modifying
    @Query(
            "UPDATE KnowledgeIngestionJob j SET j.documentId = NULL WHERE j.documentId ="
                    + " :documentId")
    void detachDocument(@Param("documentId") UUID documentId);
}
