-- Parent-child ("small-to-big") retrieval for knowledge collections (RAG quality lane, 2026-10-04).
-- Version V432 - unique across the merged controlplane/ + tenant/ Flyway stream (see V361's header).
-- Disk max when written was V425; V432 and V433 were assigned to this lane. V433 is intentionally
-- NOT used: the keyword leg reuses idx_knowledge_chunks_content_tsv (V154), so no index is needed.
--
-- TENANT-schema objects (no schema qualifier), next to knowledge_collection (V15) and
-- knowledge_chunks (V10).
--
-- 1. knowledge_collection.parent_child_enabled - a collection opts in to parent-child chunking.
--    NOT NULL DEFAULT FALSE fills every existing row, so every existing collection keeps chunking
--    and searching exactly as before. The flag only affects documents ingested AFTER it is set
--    (and only while app.knowledge.parent-child.enabled is on); stored chunks are never re-cut.
--
-- 2. knowledge_parent_chunk - the larger "parent" passages. Only the small child chunks are
--    embedded and live in knowledge_chunks (their metadata carries parent_id, child_start and
--    child_end); the parent text is stored once here and handed to the model at retrieval time.
--    Parents are never embedded, so this table has no vector column and no index on it.
--    ON DELETE CASCADE from knowledge_document: deleting a document (KnowledgeDocumentService#delete
--    removes its chunks, then the row) drops its parents too, so no orphan parent text survives.
--    document_id is nullable for the raw /ingest text endpoint, which has no owning document;
--    those parents go away with their collection (ON DELETE CASCADE).
--
-- Plain IF NOT EXISTS everywhere (not CREATE INDEX CONCURRENTLY, which Postgres refuses inside
-- Flyway's migration transaction); both new indexes are on a brand-new, empty table.
ALTER TABLE knowledge_collection
    ADD COLUMN IF NOT EXISTS parent_child_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS knowledge_parent_chunk (
    id            UUID PRIMARY KEY,
    collection_id UUID NOT NULL REFERENCES knowledge_collection(id) ON DELETE CASCADE,
    document_id   UUID REFERENCES knowledge_document(id) ON DELETE CASCADE,
    parent_index  INT NOT NULL,
    content       TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_parent_chunk_document ON knowledge_parent_chunk (document_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_parent_chunk_collection ON knowledge_parent_chunk (collection_id);
