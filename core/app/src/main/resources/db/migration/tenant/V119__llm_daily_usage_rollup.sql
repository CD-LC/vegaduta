-- Phase P4 (Spend & Reports) scale hardening, per
-- PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §8: "never scan hot tables for
-- dashboards - pre-aggregate into rollup tables (hourly/daily) via a job.
-- Reports serve from rollups, not raw rows."
--
-- One row per (day, provider, model, agent, modality) grain, pre-aggregated
-- from llm_call_log (V52) by LlmUsageRollupScheduler once a day for the
-- prior UTC day. SpendReportController serves every breakdown (by day /
-- model / provider / agent / modality) from this table instead of scanning
-- llm_call_log, and only falls back to a bounded live query over the raw
-- table for TODAY (the one day not yet rolled up). Schema-per-tenant
-- placement, same as llm_call_log itself - each tenant's rollup only ever
-- aggregates that tenant's own calls (Hibernate resolves the schema from
-- TenantContext, set per tenant by the scheduler's TenantSchemaIterator).
--
-- latency_sum_ms is kept alongside the two stored percentiles so a
-- call-weighted MEAN latency stays exact at any aggregation level (sum of
-- sums / sum of calls); the p50/p95 columns are exact only at this row's
-- own grain (percentile_cont computed by the job over that grain's raw
-- rows) - reports that collapse several grains re-weight them by call count
-- as a documented approximation, see LlmUsageRollupService's javadoc.
CREATE TABLE llm_daily_usage_rollup (
    id                  UUID PRIMARY KEY,
    usage_date          DATE NOT NULL,
    provider_name       VARCHAR(32) NOT NULL,
    model               VARCHAR(128) NOT NULL,
    -- Nullable: internal/system call sites (guardrails, graph-memory,
    -- workflow-graph gen, Ask-AI) record a null agent on llm_call_log, same
    -- as that table. Rolled up under a fixed sentinel in the unique index
    -- below so those rows still collapse to one grain per day/provider/model.
    agent_id            UUID,
    -- text | image | audio | other - derived from the model id by
    -- ModalityClassifier ("per-modality where derivable", §3/§4). llm_call_log
    -- has no modality column; this is an honest heuristic, never invented cost.
    modality            VARCHAR(16) NOT NULL,
    total_calls         BIGINT NOT NULL,
    error_count         BIGINT NOT NULL,
    prompt_tokens       BIGINT NOT NULL,
    completion_tokens   BIGINT NOT NULL,
    total_tokens        BIGINT NOT NULL,
    estimated_cost_usd  NUMERIC(16, 6) NOT NULL,
    -- Sum of latency_ms over the grain's rows -> exact weighted mean at any
    -- aggregation. Percentiles are per-grain (see header note).
    latency_sum_ms      BIGINT NOT NULL,
    latency_p50_ms      DOUBLE PRECISION,
    latency_p95_ms      DOUBLE PRECISION,
    -- Distinct session/conversation count within the grain - message/volume
    -- reporting ("token & message volume", §3). Summed across grains is an
    -- upper bound (a session can span models/agents), reported as such.
    distinct_sessions   BIGINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The rollup job is idempotent (re-runnable for a given day - backfill,
-- retry, or a manual re-aggregate must not duplicate a grain), so the grain
-- is a UNIQUE key it upserts against. agent_id is NULL for system calls and
-- Postgres treats NULLs as distinct in a plain unique index, which would let
-- two "system" grains coexist - COALESCE to the all-zero sentinel UUID so the
-- one-row-per-grain invariant holds for null-agent rows too.
CREATE UNIQUE INDEX ux_llm_daily_usage_rollup_grain
    ON llm_daily_usage_rollup (
        usage_date, provider_name, model,
        COALESCE(agent_id, '00000000-0000-0000-0000-000000000000'::uuid), modality
    );

-- Every report filters by date range first, then groups; a descending date
-- index serves both the range scan and the "most recent day" default view.
CREATE INDEX idx_llm_daily_usage_rollup_date ON llm_daily_usage_rollup (usage_date DESC);
CREATE INDEX idx_llm_daily_usage_rollup_agent ON llm_daily_usage_rollup (agent_id);
