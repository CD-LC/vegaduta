-- Crew-style multi-agent handoff (chat-time), Stage 1: the SpecialistRegistry.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into
-- ONE version stream (see V212/V260/V265's headers for the 2026-07-31
-- production outage a duplicate version number caused - a collision here is
-- an OUTAGE, not a merge conflict). Checked at authoring time (2026-09-23) by
-- numeric sort of both directories in THIS worktree: tenant/'s highest is
-- V280, controlplane/'s highest is V281. This fan-out task was assigned the
-- V304-V306 band specifically so it cannot collide with sibling tasks
-- claiming lower numbers from the same gap-radar build-out; V304 is the
-- first free slot in that band, and this file is the only V304 in the
-- repository.
--
-- WHAT THIS IS, AND HOW IT DIFFERS FROM agent_collaborator (V56)
-- -----------------------------------------------------------------
-- agent_collaborator (V56) is a fixed, per-agent opt-in whitelist: agent A
-- names agent B by id, and gets one "ask_agent_b" tool. It has a hard,
-- structural depth-1 cap (a collaborator invoked that way is never itself
-- offered a delegation tool - see AgentToolProvider#resolveToolsForDelegatedCall).
--
-- agent_specialist is a DIFFERENT, additive primitive: it marks an agent as
-- a callable SPECIALIST for a role/capability tag (e.g. "billing",
-- "legal-review"), discoverable by ANY agent in the tenant via that tag
-- rather than by a pre-wired agent id, and reachable through a multi-hop
-- handoff chain up to com.agenticai.core.agents.crew.HandoffChain#MAX_DEPTH
-- hops with cycle detection (see that class). It does not replace, gate, or
-- share state with agent_collaborator - the two coexist; a tenant can use
-- either, both, or neither.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- Same reasoning as agent_collaborator (V56) and agent_experiment (V265):
-- every row here is customer configuration keyed to a per-tenant agent, and
-- must vanish with TenantDataErasureService's DROP SCHEMA.
--
-- TENANT SCOPING
-- ---------------
-- No explicit tenant_id column - same convention as every other agent-scoped
-- table in this schema (agent_collaborator, agent_allowed_tool, ...):
-- isolation comes from the schema-per-tenant routing datasource itself (see
-- PgBouncer's POOL_MODE=session requirement in the deploy docs), not a
-- filtered column.
CREATE TABLE agent_specialist (
    id              UUID          PRIMARY KEY,
    agent_id        UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,

    -- Normalized (trimmed, lower-cased) by SpecialistRegistry before writes -
    -- stored normalized too so findByCapabilityTag can do a plain equality
    -- lookup instead of a case-insensitive scan.
    capability_tag  VARCHAR(100)  NOT NULL,

    -- Optional human-readable note shown in the delegating agent's tool
    -- description ("billing (handles refunds, invoices, ...)").
    description     VARCHAR(500),

    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- One agent registers a given capability at most once - re-registering
    -- is an update (SpecialistRegistry#register), not a duplicate row.
    CONSTRAINT uq_agent_specialist_agent_tag UNIQUE (agent_id, capability_tag)
);

-- The hot lookup: "which agent(s) are registered for capability X" - the
-- entire dispatch decision in SpecialistHandoffService#findSpecialist.
CREATE INDEX idx_agent_specialist_capability_tag ON agent_specialist (capability_tag);

-- "Every capability this agent is registered for" - agent-detail/admin views.
CREATE INDEX idx_agent_specialist_agent_id ON agent_specialist (agent_id);
