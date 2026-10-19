-- Authenticated video generation (core/imagegen) - the async enqueue/poll
-- job row for Vertex AI Veo requests, same shape as V17's
-- knowledge_ingestion_job (that migration's header comment covers the
-- general "why async" reasoning; this repeats it for a slow provider call
-- instead of ingestion).
--
-- Deliberately tenant-schema (unschema'd, unlike the anonymous demo path's
-- control_plane.demo_generated_video in V142) - this is an authenticated
-- feature served through a real JWT-checked controller, so there is no
-- capability-URL problem to solve the way the anonymous image/video demo
-- endpoints have.
--
-- No separate usage-counter table: VideoGenerationQuotaService counts rows
-- in this table directly (countByCreatedAtAfterAndStatusNot) rather than
-- maintaining a redundant tally, since a job row already exists per attempt
-- and video generation's volume is nowhere near "count(*) is too slow".

CREATE TABLE video_generation_job (
    id                     UUID PRIMARY KEY,
    prompt                 TEXT         NOT NULL,
    model                  VARCHAR(128) NOT NULL,
    duration_seconds       INTEGER      NOT NULL,
    -- "GOOGLE_VERTEX_BYOK" or "GOOGLE_VERTEX_POOL" - which credential path
    -- served this request (see VideoGenerationJobService#enqueue). Not a
    -- ProviderName FK/enum column: this distinguishes credential SOURCE,
    -- not provider identity, and the provider is always Vertex today.
    provider_name          VARCHAR(32)  NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    -- The Vertex long-running-operation name while PROCESSING. For the pool
    -- credential path this is "{projectId}|{location}|{operationName}" (see
    -- VertexVeoVideoGenerationClient#submitFromPool) so a poll can find the
    -- right pool slot again without a second lookup table.
    provider_operation_id  VARCHAR(512),
    result_object_key      VARCHAR(512),
    result_content_type    VARCHAR(64),
    error_message          TEXT,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL
);

-- VideoGenerationJobRunner's poll sweep: "every PROCESSING job in this schema".
CREATE INDEX idx_video_generation_job_status ON video_generation_job (status);

-- VideoGenerationQuotaService's daily/monthly usage count.
CREATE INDEX idx_video_generation_job_created_at ON video_generation_job (created_at);
