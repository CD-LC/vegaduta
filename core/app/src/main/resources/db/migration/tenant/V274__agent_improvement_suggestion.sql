-- Agent improvement suggestions (Tier-3) - the "so fix it" half of the
-- auto-improvement loop. PlatformIssue already clusters production
-- failures (fingerprint dedup + occurrence_count); this table parks the
-- LLM-distilled, admin-reviewable fix each recurring cluster suggests.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into
-- ONE version stream, so a number is free only when it is free in BOTH
-- folders (see V273's header for the full paragraph - a collision is an
-- OUTAGE, not a merge conflict). V274 was reserved for this feature by the
-- Tier-3 work stream in docs/AGENT-COORDINATION.md; V270-V273 are its
-- siblings in the same stream.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- suggested_text is verbatim customer configuration (a replacement system
-- prompt or a procedure body). A control-plane copy would survive
-- TenantDataErasureService's DROP SCHEMA still holding customer prompt
-- content - a right-to-erasure defect. It lives beside `agent` and
-- `platform_issue`, and dies with them.

CREATE TABLE agent_improvement_suggestion (
    id               UUID          PRIMARY KEY,

    -- Redundant with the tenant schema for isolation - kept for
    -- cross-schema operator tooling and audit correlation, same
    -- convention as procedures.tenant_id (V270).
    tenant_id        VARCHAR(255),

    -- The agent this suggestion proposes changing. No FK on purpose:
    -- platform_issue (V70) references no agent table either, and a
    -- deleted agent leaves suggestions that are harmless history - the
    -- accept path re-validates the agent exists at apply time.
    agent_id         UUID          NOT NULL,

    -- The platform_issue cluster this suggestion was distilled from.
    -- NULL is legal for future ad-hoc suggestion sources.
    issue_id         UUID,

    -- PROMPT_REVISION = replace the agent's system prompt;
    -- NEW_PROCEDURE  = create + attach a procedures-layer rule set (V270).
    kind             VARCHAR(32)   NOT NULL,

    title            VARCHAR(255)  NOT NULL,
    rationale        TEXT,

    -- The FULL artifact accept() applies (whole replacement prompt / whole
    -- procedure body), stored verbatim so the admin reviews the exact
    -- bytes before deciding - never a diff.
    suggested_text   TEXT          NOT NULL,

    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',

    -- What accept() actually applied: 'systemPrompt' or
    -- 'procedure:<uuid>' - so the audit trail answers "where did that
    -- change come from" without guessing.
    applied_ref      VARCHAR(255),

    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ,
    decided_by       VARCHAR(255),

    CONSTRAINT agent_improvement_suggestion_kind_chk
        CHECK (kind IN ('PROMPT_REVISION','NEW_PROCEDURE')),
    CONSTRAINT agent_improvement_suggestion_status_chk
        CHECK (status IN ('PENDING','ACCEPTED','DISMISSED'))
);

-- The queue read: "this agent's suggestions, newest first."
CREATE INDEX idx_agent_improvement_agent_created
    ON agent_improvement_suggestion (agent_id, created_at DESC);

-- The dedupe read: "does this cluster already have a live suggestion?"
CREATE INDEX idx_agent_improvement_issue_status
    ON agent_improvement_suggestion (issue_id, status);
