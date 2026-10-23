-- V662: agent runtime limits - per-agent and per-tenant overrides of the turn's tool-step ceiling, timeouts,
-- tool-output cap, knowledge-retrieval top-k / min-score and continuation passes.
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with controlplane/.
--
-- Every column is NULLABLE and NULL means "not overridden here": the layer below decides (tenant defaults, then the
-- platform's application.yml), so no pre-existing agent or tenant changes behaviour. Business bounds (the least and
-- most each value may take) are NOT enforced here: they are configuration (app.agent-runtime.bounds.*) and are
-- checked by the API, so tightening or loosening one needs no migration. The CHECKs below only reject nonsense.
ALTER TABLE agent
    ADD COLUMN IF NOT EXISTS max_tool_steps        INTEGER          NULL CHECK (max_tool_steps IS NULL OR max_tool_steps > 0),
    ADD COLUMN IF NOT EXISTS turn_timeout_seconds  INTEGER          NULL CHECK (turn_timeout_seconds IS NULL OR turn_timeout_seconds > 0),
    ADD COLUMN IF NOT EXISTS tool_timeout_seconds  INTEGER          NULL CHECK (tool_timeout_seconds IS NULL OR tool_timeout_seconds > 0),
    ADD COLUMN IF NOT EXISTS tool_output_max_chars INTEGER          NULL CHECK (tool_output_max_chars IS NULL OR tool_output_max_chars > 0),
    ADD COLUMN IF NOT EXISTS retrieval_top_k       INTEGER          NULL CHECK (retrieval_top_k IS NULL OR retrieval_top_k > 0),
    ADD COLUMN IF NOT EXISTS retrieval_min_score   DOUBLE PRECISION NULL CHECK (retrieval_min_score IS NULL OR retrieval_min_score >= 0),
    ADD COLUMN IF NOT EXISTS continuation_passes   INTEGER          NULL CHECK (continuation_passes IS NULL OR continuation_passes >= 0);

-- The tenant's defaults for every agent of the tenant: at most one row (id is pinned to 1), the same seven
-- columns as above. No row, or a NULL column, means the platform default.
CREATE TABLE IF NOT EXISTS tenant_runtime_limits (
    id                    SMALLINT         PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    max_tool_steps        INTEGER          NULL CHECK (max_tool_steps IS NULL OR max_tool_steps > 0),
    turn_timeout_seconds  INTEGER          NULL CHECK (turn_timeout_seconds IS NULL OR turn_timeout_seconds > 0),
    tool_timeout_seconds  INTEGER          NULL CHECK (tool_timeout_seconds IS NULL OR tool_timeout_seconds > 0),
    tool_output_max_chars INTEGER          NULL CHECK (tool_output_max_chars IS NULL OR tool_output_max_chars > 0),
    retrieval_top_k       INTEGER          NULL CHECK (retrieval_top_k IS NULL OR retrieval_top_k > 0),
    retrieval_min_score   DOUBLE PRECISION NULL CHECK (retrieval_min_score IS NULL OR retrieval_min_score >= 0),
    continuation_passes   INTEGER          NULL CHECK (continuation_passes IS NULL OR continuation_passes >= 0),
    updated_at            TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_by            VARCHAR(255)     NULL
);
