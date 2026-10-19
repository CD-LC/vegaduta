-- Simulation / adversarial persona testing - a second "CI for agents"
-- surface, sibling to eval datasets (V213) and the eval gate (V260) but
-- answering a different question: not "does this agent's answer match a
-- golden expected output", but "does this agent hold up against a simulated
-- adversarial user trying to break it" (prompt injection, jailbreak framing,
-- system-prompt exfiltration, PII phishing, off-brand bait, abuse, scope
-- creep, policy abuse).
--
-- VERSION NUMBER
-- ---------------
-- Same shared controlplane/tenant Flyway namespace as every migration in
-- this line (see V212/V260/V265's headers for the outage a collision here
-- would cause). Checked at authoring time (2026-09-23) immediately after
-- claiming V265 for agent_experiment: tenant/'s highest is now V265,
-- controlplane/'s highest is V264. V266 is the next slot free in both, and
-- this file is the only V266 in the repository.
--
-- WHY TENANT SCHEMA
-- ------------------
-- Same reasoning as every table in this feature line: sessions/runs carry
-- raw model output (the simulated adversarial transcript) keyed to a
-- per-tenant agent, and custom personas are tenant-authored content - both
-- must vanish with TenantDataErasureService's DROP SCHEMA.
--
-- BUILT-IN PERSONAS ARE CODE, NOT ROWS
-- --------------------------------------
-- simulation_persona holds ONLY tenant-authored custom personas. The ~6-8
-- built-in adversarial categories (prompt injection, jailbreak, exfiltration,
-- phishing, off-brand bait, abuse, scope creep, policy abuse) are a static
-- list in SimulationPersonaService - no tenant should be able to edit or
-- delete the platform's own baseline coverage, and a fixed key namespace
-- lets a run row's persona_key stay meaningful without a join.

CREATE TABLE simulation_persona (
    id           UUID          PRIMARY KEY,
    key          VARCHAR(64)   NOT NULL,
    name         VARCHAR(255)  NOT NULL,

    -- What the persona IS - shown to a human choosing personas for a session.
    description  TEXT          NOT NULL,

    -- What the adversarial persona is TRYING TO ACHIEVE - embedded verbatim
    -- into the persona-simulator's system prompt (SimulationRunService) and
    -- into the verdict judge's prompt, so the judge scores against the same
    -- goal the simulator was chasing.
    objective    TEXT          NOT NULL,

    created_by   VARCHAR(255),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Custom keys must not collide with each other (built-in keys live in code
-- and are never written here, so no cross-namespace check is needed at the
-- database level; SimulationPersonaService#resolvePersonas prefers a custom
-- row over a built-in of the same key, documented there).
CREATE UNIQUE INDEX uq_simulation_persona_key ON simulation_persona (key);

CREATE TABLE simulation_session (
    id             UUID          PRIMARY KEY,

    -- The target agent. ON DELETE CASCADE: a simulation of a deleted agent
    -- is unrestartable and its transcripts are meaningless without the agent
    -- they were run against - same posture as agent_version (V212).
    agent_id       UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,

    -- Comma-joined persona keys (built-in and/or custom) this session was
    -- launched with - denormalized copy so the session list renders without
    -- a join to simulation_run for every row's persona set.
    persona_keys   TEXT          NOT NULL,

    max_turns      INTEGER       NOT NULL,

    -- CHECK for the same @Enumerated(STRING) reason every status column in
    -- this feature line carries one (see V212/V260's headers).
    status         VARCHAR(16)   NOT NULL,

    created_by     VARCHAR(255),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,

    CONSTRAINT simulation_session_status_chk CHECK (status IN ('QUEUED','RUNNING','COMPLETED'))
);

-- The list read: "every simulation session ever run against this agent, newest first."
CREATE INDEX idx_simulation_session_agent_id ON simulation_session (agent_id, created_at DESC);

CREATE TABLE simulation_run (
    id               UUID          PRIMARY KEY,

    -- ON DELETE CASCADE from the session - a run has no independent meaning
    -- once its parent session is gone (same shape as eval_run_case_result
    -- under eval_run).
    session_id       UUID          NOT NULL REFERENCES simulation_session(id) ON DELETE CASCADE,

    -- Denormalized persona identity (name/key), NOT a foreign key to
    -- simulation_persona: a built-in persona has no row there at all, and a
    -- custom persona could be edited or deleted after the run - a run's
    -- record of "which persona, described how" must survive either.
    persona_key      VARCHAR(64)   NOT NULL,
    persona_name     VARCHAR(255)  NOT NULL,

    status           VARCHAR(16)   NOT NULL,

    -- The full simulator<->target transcript, JSON array of turns. TEXT, not
    -- JSONB, for the same reason agent_version.config_json and
    -- workflow_run.trace_json are TEXT: written whole, read back whole,
    -- nothing ever queries INTO it.
    transcript_json  TEXT,

    -- Null until the verdict judge has run; PASSED/FAILED/INCONCLUSIVE, same
    -- three-valued honest-absence contract as eval_run.verdict (V213) - "the
    -- judge measured nothing" must never collapse into either a pass or a
    -- fail.
    verdict          VARCHAR(16),
    verdict_reason   TEXT,

    turn_count       INTEGER       NOT NULL DEFAULT 0,

    -- Per-run error isolation (SimulationRunService#executeRun): one
    -- persona's failure is caught and recorded here, never allowed to abort
    -- the session or any sibling run.
    error_message    TEXT,

    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,

    CONSTRAINT simulation_run_status_chk CHECK (status IN ('QUEUED','RUNNING','PASSED','FAILED','ERROR')),
    CONSTRAINT simulation_run_verdict_chk CHECK (verdict IS NULL OR verdict IN ('PASSED','FAILED','INCONCLUSIVE'))
);

-- The session detail read: "every run belonging to this session."
CREATE INDEX idx_simulation_run_session_id ON simulation_run (session_id);

-- The boot orphan sweep's read (SimulationRunExecutor, sibling of
-- EvalRunExecutor) - partial because QUEUED/RUNNING rows are a vanishing
-- fraction of the table once runs accumulate.
CREATE INDEX idx_simulation_run_live ON simulation_run (status)
    WHERE status IN ('QUEUED','RUNNING');
