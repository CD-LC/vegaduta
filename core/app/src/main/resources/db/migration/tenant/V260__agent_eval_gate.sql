-- Eval gate on promote ("CI for agents", workstream "P2 Operate layer") -
-- the enforcement half of V213's eval datasets. V213 made verdicts PUBLISHED;
-- this migration adds the two things a gate needs that did not exist:
--
--   1. eval_run.agent_version_id - WHICH agent_version row the run replayed.
--      V213 runs recorded agent_id only, so a gate could never tell whether a
--      PASSED verdict belongs to the configuration being promoted or to an
--      edit that has since been overwritten. Bound at trigger time to the
--      agent's HEAD version row (the newest snapshot is by convention the
--      live config - see V212's header and AgentVersion's javadoc). Nullable:
--      runs from before this column existed stay readable and simply never
--      satisfy a gate - honest absence, same posture as V213's verdict rules.
--
--   2. agent_eval_gate - the per-agent gate CONFIG. One row per agent at
--      most (unique agent_id): which dataset must pass, whether the gate is
--      on, and how fresh the passing run must be. A promote of a version to
--      PRODUCTION through AgentVersionService#setLabel consults this row via
--      the AgentPromotionGate SPI; see EvalGateService for the verdict rules.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream, so a number is free only when it is free in BOTH folders
-- (see V212/V213's headers and docs/deploy/ZERO-DOWNTIME-DEPLOY-RULES.md for
-- the 2026-07-31 production outage a duplicate V156 caused; a collision here
-- is an OUTAGE, not a merge conflict). Checked at authoring time
-- (2026-09-23): repo's highest is V240 (teams_channel). The coordination log
-- records V241+ claimed by the P1-extensibility session and V250-V259 by the
-- P2-completeness session, both in flight; V260 is the next slot past all
-- claimed ranges, and this file is the only V260 in the repository.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- Same reasoning as every table in this feature line (V212/V213 headers):
-- gate rows reference eval_dataset (tenant schema) and eval_run carries raw
-- model output; both must vanish with TenantDataErasureService's DROP SCHEMA.

ALTER TABLE eval_run
    ADD COLUMN agent_version_id UUID;

-- The gate query: "latest SUCCEEDED run of this dataset against a version
-- whose config is byte-identical to the version being promoted" - evaluated
-- as (dataset, version, status) with a finished_at ordering, so the index
-- covers all three lookup columns. Partial: pre-V260 rows (null version) can
-- never qualify a promote anyway, so they do not belong in the index.
CREATE INDEX idx_eval_run_dataset_version_status ON eval_run (dataset_id, agent_version_id, status)
    WHERE agent_version_id IS NOT NULL;

CREATE TABLE agent_eval_gate (
    id                  UUID          PRIMARY KEY,

    -- One gate per agent - "which dataset must pass before this agent's
    -- config may be called PRODUCTION" is a single-valued policy. UNIQUE,
    -- not a PK on agent_id, so the row has its own stable identity for
    -- audit references.
    agent_id            UUID          NOT NULL UNIQUE,

    -- The dataset whose verdict the gate consults. FK + CASCADE: when the
    -- dataset is deleted there is nothing left to gate against, and leaving
    -- the row behind would block every promote forever with a gate nobody
    -- can satisfy - a self-inflicted lockout, not a safety feature. Deleting
    -- the gate with its dataset is the honest outcome (the tenant can
    -- configure a new one; an admin force-override also exists).
    dataset_id          UUID          NOT NULL REFERENCES eval_dataset (id) ON DELETE CASCADE,

    -- Master switch, kept as a column rather than "delete the row to
    -- disable" so the configured dataset survives a temporary suspension.
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,

    -- Freshness window for the qualifying run, in hours. A PASSED verdict
    -- from three weeks ago does not cover a config promoted today when the
    -- tenant set a window. NULL means "any age qualifies" - the version
    -- binding already guarantees the run tested THIS configuration, so age
    -- is only meaningful to tenants who additionally want "tested recently".
    max_run_age_hours   INTEGER,

    -- Keycloak subject, opaque VARCHAR, never FK'd - same convention as
    -- eval_dataset.created_by (V213) and user_permission_grant (V199).
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The read path: gate config for one agent (the promote check), plus the
-- console's "does this agent have a gate" probe.
CREATE INDEX idx_agent_eval_gate_dataset ON agent_eval_gate (dataset_id);
