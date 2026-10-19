-- "Build the backend to make it real" - the AXON Knowledge screen's design
-- handoff shows a live per-file ingestion pipeline (queued -> extracting ->
-- embedding -> ready) and a full file table with real chunk counts. Neither
-- existed before this migration: the sync upload path
-- (KnowledgeController.upload, for pdf/docx/html/md/image) was completely
-- stateless after its single blocking HTTP response, and the async path
-- (KnowledgeIngestionJob, audio/video) only tracked QUEUED/PROCESSING/
-- COMPLETE/FAILED with no per-stage granularity and no long-lived row once
-- COMPLETE (audio_content gets nulled, but the row itself does persist -
-- however it has no chunk_count exposed via any DTO and no filename/status
-- view usable as a general "files in this collection" inventory).
--
-- knowledge_document is the single source of truth for both paths going
-- forward: KnowledgeController.upload creates a row immediately (status
-- UPLOADED) before doing any work, KnowledgeIngestionService transitions it
-- through EXTRACTING/EMBEDDING as those real steps actually happen, and
-- marks READY (with a real chunk_count) or FAILED (with a real
-- error_message) at the end - see KnowledgeDocumentStatus's javadoc for why
-- these are genuine, disclosed pipeline stages rather than a fabricated
-- ticking percentage.
CREATE TABLE knowledge_document (
    id              UUID PRIMARY KEY,
    collection_id   UUID NOT NULL REFERENCES knowledge_collection(id),
    filename        VARCHAR(500) NOT NULL,
    file_extension  VARCHAR(16),
    file_size_bytes BIGINT NOT NULL,
    status          VARCHAR(32) NOT NULL,
    chunk_count     INT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- KnowledgeDocumentRepository.findByCollectionIdOrderByCreatedAtDesc - the
-- file table's real query, newest upload first.
CREATE INDEX idx_knowledge_document_collection ON knowledge_document (collection_id, created_at DESC);

-- Links the async job back to the document row created for it at enqueue
-- time, so KnowledgeIngestionJobService.processJob can update the same
-- document the sync path would have created, instead of the two paths
-- diverging into separate tracking mechanisms.
ALTER TABLE knowledge_ingestion_job ADD COLUMN document_id UUID REFERENCES knowledge_document(id);
