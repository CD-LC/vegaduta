-- Shadow vector storage for knowledge re-embed jobs (Spring AI gap-fix,
-- 2026-09-28). Version V363 - unique across the merged controlplane/ +
-- tenant/ Flyway stream (see V361's header).
--
-- TENANT-schema table (no schema qualifier), next to knowledge_chunks (V10).
-- Raw JDBC only, never Hibernate-mapped (pgvector's vector type has no
-- Hibernate mapping - same reason knowledge_chunks itself isn't).
--
-- While a collection is REEMBEDDING, each chunk's NEW-model vector is written
-- here, keyed by (chunk_id, embedding_model) - never into knowledge_chunks -
-- so live search keeps reading the old, complete, self-consistent vector set.
-- The key makes writes idempotent (ON CONFLICT DO UPDATE): a crashed or
-- restarted job resumes by embedding only chunks with no shadow row yet, and a
-- job retried after FAILED reuses everything already computed for that model.
--
-- The flip (KnowledgeReembedService) copies these vectors onto
-- knowledge_chunks and switches knowledge_collection.embedding_model in ONE
-- transaction, then deletes the collection's shadow rows - the old vectors are
-- overwritten in place, so nothing stale is left behind.
--
-- ON DELETE CASCADE from knowledge_chunks: deleting a document mid-job
-- (KnowledgeDocumentService#delete removes its chunks) drops its shadow rows
-- too, so the flip can never resurrect a deleted chunk's vector.
CREATE TABLE knowledge_chunk_reembed_shadow (
    chunk_id         UUID NOT NULL REFERENCES knowledge_chunks(id) ON DELETE CASCADE,
    embedding_model  VARCHAR(64) NOT NULL,
    collection_id    UUID NOT NULL REFERENCES knowledge_collection(id) ON DELETE CASCADE,
    embedding        vector(768) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chunk_id, embedding_model)
);

CREATE INDEX idx_knowledge_chunk_reembed_shadow_collection
    ON knowledge_chunk_reembed_shadow (collection_id, embedding_model);
