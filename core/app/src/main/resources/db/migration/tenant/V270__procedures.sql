-- Procedures / SOP layer (Tier-3 gap): natural-language business rules with
-- deterministic enforcement, sitting between "system prompt" and "workflow
-- graph". A procedure is a named, ordered rule list attachable to one or
-- more agents; each rule is a natural-language instruction plus an
-- OPTIONAL machine-checkable guard clause (procedure_rule.guard_kind) that
-- GuardedToolCallback enforces deterministically via
-- ProcedureGuardEvaluator - no LLM calls in the enforcement path.
--
-- Tenant-schema tables (same convention as guardrail_rule / skill): every
-- tenant gets their own copy; tenant_id columns exist for cross-schema
-- operator tooling only, not for isolation.
--
-- Version V270: controlplane/ and tenant/ share ONE Flyway version stream.
-- V241-V249 are the P1 session's, V250-V259 P2-completeness, V260-V269
-- P2-operate + landed overflow (V262/V263 music, V264 rating-stars fix).
-- This session claims V270-V289 per docs/AGENT-COORDINATION.md.

CREATE TABLE IF NOT EXISTS procedures (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(255),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_by  VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS procedure_rule (
    id           UUID PRIMARY KEY,
    procedure_id UUID        NOT NULL REFERENCES procedures (id) ON DELETE CASCADE,
    rule_order   INTEGER     NOT NULL,
    instruction  TEXT        NOT NULL,
    guard_kind   VARCHAR(40),
    guard_tool   VARCHAR(255),
    guard_config JSONB
);

CREATE INDEX IF NOT EXISTS idx_procedure_rule_procedure ON procedure_rule (procedure_id, rule_order);

CREATE TABLE IF NOT EXISTS agent_procedure (
    id           UUID PRIMARY KEY,
    agent_id     UUID        NOT NULL,
    procedure_id UUID        NOT NULL REFERENCES procedures (id) ON DELETE CASCADE,
    position     INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_procedure UNIQUE (agent_id, procedure_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_procedure_agent ON agent_procedure (agent_id, position);
