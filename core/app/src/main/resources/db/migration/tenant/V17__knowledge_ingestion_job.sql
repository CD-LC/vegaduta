-- Knowledge Stage F3c - audio transcription is slow enough (minutes on
-- CPU for a real recording) that holding an HTTP request open for it is a
-- bad user experience and an easy way to trip proxy/load-balancer
-- timeouts (see the Stage F Build Plan's F3c decision). This table backs
-- the async "upload returns immediately, poll for status" pattern:
-- POST /api/knowledge/upload-audio creates a QUEUED row and returns its id
-- right away; KnowledgeIngestionJobRunner (a @Scheduled job, via
-- TenantSchemaIterator - same multi-tenancy-safe iteration
-- RetryFailedActionsJob already uses) picks up QUEUED rows, transcribes
-- via the sidecar, chunks+embeds+stores exactly like every other format,
-- and marks the row COMPLETE or FAILED.
--
-- audio_content holds the raw uploaded bytes only until the job runner has
-- processed them (nulled out afterward, win or lose, to reclaim space -
-- see KnowledgeIngestionJobService) - a real, stated limitation: this is
-- Postgres bytea, not object storage, so this table is not meant to hold
-- a large backlog of unprocessed audio for long. Fine for the job runner's
-- short-poll-interval design; a genuine scale-out concern is out of scope
-- for this stage (see the Build Plan's Section 5).
CREATE TABLE knowledge_ingestion_job (
    id                 UUID PRIMARY KEY,
    filename           VARCHAR(500) NOT NULL,
    collection_id      UUID NOT NULL REFERENCES knowledge_collection(id),
    chunk_size         INT NOT NULL,
    chunk_overlap      INT NOT NULL,
    status             VARCHAR(32) NOT NULL,
    error_message      TEXT,
    result_chunk_count INT,
    audio_content      BYTEA,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The job runner's poll query - see KnowledgeIngestionJobRepository.
CREATE INDEX idx_knowledge_ingestion_job_status ON knowledge_ingestion_job (status);
