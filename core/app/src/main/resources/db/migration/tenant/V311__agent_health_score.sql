-- Meta-agent supervisor (gap-radar item 6, Intercom "Fin Operator"-style):
-- the live, per-agent health/confidence snapshot that AgentSupervisorSweep
-- writes every tick. This is deliberately NOT a second suggestion system -
-- suggestion generation stays entirely inside AgentImprovementService
-- (V274, agent_improvement_suggestion); this table only records "how is
-- this agent doing right now" so the console can surface it without
-- re-querying platform_issue + agent_improvement_suggestion on every page
-- load.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into
-- ONE version stream (see V274's header for the full paragraph - a
-- collision is an OUTAGE, not a merge conflict). Checked at authoring time
-- (2026-09-23) against the actual migration directories in this worktree:
-- tenant max is V280 (resolution_event), controlplane max is V281
-- (resolution_pricing_settings). This file claims V311, inside the
-- V311-V329 band reserved for the "meta-agent supervisor" item of the
-- 2026-09-23 gap-radar fan-out (docs/AGENT-COORDINATION.md, "CLAIM:
-- Gap-radar build-out").
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- Same reasoning as V274: this is a live signal about one tenant's own
-- agent (name, prompt-derived behaviour, its own issue history) and must
-- vanish with TenantDataErasureService's DROP SCHEMA, not survive it in a
-- control-plane copy. It lives beside `agent`, `platform_issue`, and
-- `agent_improvement_suggestion`, and dies with them.
--
-- ONE ROW PER AGENT
-- -----------------
-- Unlike agent_improvement_suggestion (append-only history), this table is
-- a snapshot: AgentSupervisorSweep upserts the single row per agent_id
-- every tick, overwriting the prior score. History of how a score moved
-- over time is not a goal here - platform_issue/agent_improvement_suggestion
-- already carry the auditable trail this score is computed from.

CREATE TABLE agent_health_score (
    id                        UUID          PRIMARY KEY,

    -- Redundant with the tenant schema for isolation - kept for
    -- cross-schema operator tooling, same convention as
    -- agent_improvement_suggestion.tenant_id (V274).
    tenant_id                 VARCHAR(255),

    -- One live snapshot per agent. No FK, same reasoning as
    -- agent_improvement_suggestion.agent_id (V274): a deleted agent simply
    -- stops being swept, and a stale row is harmless history until the next
    -- sweep upserts or a cleanup job (none yet) removes it.
    agent_id                  UUID          NOT NULL UNIQUE,

    -- 0-100 composite health/confidence score: 100 = no open recurring
    -- issues and nothing pending review; lower = more/worse open issue
    -- clusters and/or unreviewed improvement suggestions piling up. See
    -- AgentHealthScoreService for the exact formula.
    score                     INTEGER       NOT NULL,

    -- Coarse bucket of `score` for UI badges without re-deriving thresholds
    -- client-side.
    status                    VARCHAR(16)   NOT NULL,

    -- Inputs the score was computed from, kept alongside it so the console
    -- can explain a number instead of just showing it.
    open_issue_count          INTEGER       NOT NULL DEFAULT 0,
    pending_suggestion_count  INTEGER       NOT NULL DEFAULT 0,

    last_swept_at             TIMESTAMPTZ   NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT agent_health_score_status_chk
        CHECK (status IN ('HEALTHY','AT_RISK','DEGRADED')),
    CONSTRAINT agent_health_score_score_chk
        CHECK (score BETWEEN 0 AND 100)
);

-- The console read: "which agents need attention right now."
CREATE INDEX idx_agent_health_score_status ON agent_health_score (status);
