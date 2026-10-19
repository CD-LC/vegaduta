-- Per-agent cost and ROI view (branch gap/roi, step 1).
--
-- What this adds, and why each piece exists (all additive, every statement is
-- idempotent, so it is safe on tenant schemas that already hold data and on a
-- re-run):
--
--   1. llm_call_log.reasoning_tokens - the one per-call fact the cost view
--      needs that was NOT recorded. LlmCallLogService prices reasoning tokens
--      into estimated_cost_usd but never stored the count, so a cost computed
--      later from a price table would silently under-count thinking calls.
--      Nullable, no default: NULL means "not reported / not applicable",
--      never a fabricated 0. Adding a nullable column is a metadata-only
--      change in Postgres, no table rewrite.
--
--   2. llm_price - tenant-editable, versioned $/million-token rates. There is
--      deliberately NO seed row and no fallback rate: a model with no row (or
--      with a NULL rate the call needs) is "unknown" and is reported as
--      unpriced, never as a made-up number. A new price is a new row with its
--      own effective_from; history is kept so last month's cost does not change
--      when this month's price does. USD only, because llm_call_log is USD.
--
--   3. agent_value_assumption - what the TENANT says one resolved conversation
--      is worth (money, and/or minutes saved). Nullable, no defaults: an agent
--      with no row has no ROI figure. The currency travels with the value.
--
--   4. conversation_correction - an explicit "a human corrected this
--      conversation" mark. Corrections that arrive as turn_feedback EDIT rows
--      are NOT copied here; they are read from turn_feedback directly so there
--      is one source of truth.
--
--   5. roi_budget_alert - one row per (month, threshold) once a spend alert has
--      been raised, so the 80% / 100% notifications fire exactly once a month.
--
-- Schema-per-tenant: no tenant_id column anywhere (see V52__llm_call_log.sql).

ALTER TABLE llm_call_log ADD COLUMN IF NOT EXISTS reasoning_tokens BIGINT;

CREATE TABLE IF NOT EXISTS llm_price (
    id                     UUID          PRIMARY KEY,
    provider_name          VARCHAR(32)   NOT NULL,
    -- Matched as a case-insensitive PREFIX of llm_call_log.model; the longest
    -- matching prefix wins, then the latest effective_from not after the call.
    model_prefix           VARCHAR(128)  NOT NULL,
    -- USD per million tokens. NULL = that rate is unknown.
    input_per_million      NUMERIC(14,6),
    output_per_million     NUMERIC(14,6),
    cache_write_per_million NUMERIC(14,6),
    cache_read_per_million NUMERIC(14,6),
    effective_from         DATE          NOT NULL,
    set_by                 VARCHAR(255),
    set_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    note                   VARCHAR(300),
    CONSTRAINT llm_price_nonneg_chk CHECK (
        COALESCE(input_per_million, 0) >= 0 AND COALESCE(output_per_million, 0) >= 0
        AND COALESCE(cache_write_per_million, 0) >= 0 AND COALESCE(cache_read_per_million, 0) >= 0),
    CONSTRAINT llm_price_version_uq UNIQUE (provider_name, model_prefix, effective_from)
);

CREATE TABLE IF NOT EXISTS agent_value_assumption (
    agent_id                     UUID          PRIMARY KEY REFERENCES agent(id) ON DELETE CASCADE,
    value_per_resolution         NUMERIC(14,4),
    value_currency               CHAR(3),
    minutes_saved_per_resolution NUMERIC(10,2),
    updated_by                   VARCHAR(255),
    updated_at                   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT agent_value_nonneg_chk CHECK (
        COALESCE(value_per_resolution, 0) >= 0 AND COALESCE(minutes_saved_per_resolution, 0) >= 0),
    CONSTRAINT agent_value_currency_chk CHECK ((value_per_resolution IS NULL) = (value_currency IS NULL))
);

CREATE TABLE IF NOT EXISTS conversation_correction (
    id               UUID          PRIMARY KEY,
    conversation_key VARCHAR(160)  NOT NULL,
    agent_id         UUID          NOT NULL,
    marked_by        VARCHAR(255),
    note             VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT conversation_correction_uq UNIQUE (conversation_key)
);

CREATE INDEX IF NOT EXISTS idx_conversation_correction_agent ON conversation_correction (agent_id, created_at);

CREATE TABLE IF NOT EXISTS roi_budget_alert (
    month_start DATE         NOT NULL,
    threshold   SMALLINT     NOT NULL,
    spend_usd   NUMERIC(18,6) NOT NULL,
    cap_usd     NUMERIC(18,6) NOT NULL,
    fired_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (month_start, threshold),
    CONSTRAINT roi_budget_alert_threshold_chk CHECK (threshold IN (80, 100))
);
