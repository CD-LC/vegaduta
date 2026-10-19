-- Re-embed a knowledge collection with a different embedding model
-- (Spring AI gap-fix, 2026-09-28). Version V362 - unique across the merged
-- controlplane/ + tenant/ Flyway stream (see V361's header).
--
-- TENANT-schema tables (no schema qualifier), alongside knowledge_collection
-- (V15) and knowledge_ingestion_job (V17), whose shape this job table follows.
--
-- knowledge_collection.embedding_status: READY, or REEMBEDDING while a
-- knowledge_reembed_job builds shadow vectors (V363) for a new model. Search
-- keeps using the collection's current embedding_model the whole time; the
-- switch is one transaction at the end (KnowledgeReembedService#flip).
-- NOT NULL DEFAULT 'READY' fills every existing row, so no backfill UPDATE.
ALTER TABLE knowledge_collection
    ADD COLUMN embedding_status VARCHAR(32) NOT NULL DEFAULT 'READY';

-- status: QUEUED -> EMBEDDING -> SWEEPING -> COMPLETED, or FAILED from any
-- non-terminal state (see KnowledgeReembedJobStatus). processed_chunks is
-- recomputed from the shadow table on every batch rather than incremented, so
-- a resumed job reports true progress, never a double count.
CREATE TABLE knowledge_reembed_job (
    id                UUID PRIMARY KEY,
    collection_id     UUID NOT NULL REFERENCES knowledge_collection(id) ON DELETE CASCADE,
    source_model      VARCHAR(64) NOT NULL,
    target_model      VARCHAR(64) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    total_chunks      BIGINT NOT NULL DEFAULT 0,
    processed_chunks  BIGINT NOT NULL DEFAULT 0,
    error_message     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX idx_knowledge_reembed_job_status ON knowledge_reembed_job (status);
CREATE INDEX idx_knowledge_reembed_job_collection ON knowledge_reembed_job (collection_id, created_at DESC);

-- At most one active job per collection, enforced by the database rather than
-- only by a check-then-insert in Java. The predicate is a constant IN-list
-- (IMMUTABLE) - no now()/CURRENT_TIMESTAMP, which Postgres rejects in index
-- predicates (see V357's note).
CREATE UNIQUE INDEX uq_knowledge_reembed_job_active
    ON knowledge_reembed_job (collection_id)
    WHERE status IN ('QUEUED', 'EMBEDDING', 'SWEEPING');
