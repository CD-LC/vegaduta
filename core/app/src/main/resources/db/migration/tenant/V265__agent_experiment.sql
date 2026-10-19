-- A/B experiments on agent versions (traffic splitting + outcome/cost
-- metrics) - "CI for agents" sibling to the eval gate (V260): where the gate
-- answers "may this config be promoted", an experiment answers "is this
-- config's TREATMENT actually better than what is live, under real traffic,
-- before it is ever promoted."
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into
-- ONE version stream (see V212/V260's headers for the 2026-07-31 production
-- outage a duplicate version number caused - a collision here is an OUTAGE,
-- not a merge conflict). Checked at authoring time (2026-09-23) by numeric
-- sort of both directories: tenant/'s highest is V262, controlplane/'s
-- highest is V264. V265 is the next slot free in both, and this file is the
-- only V265 in the repository.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- Same reasoning as agent_version (V212) and agent_eval_gate (V260): every
-- row here is customer configuration or telemetry keyed to a per-tenant
-- agent, and must vanish with TenantDataErasureService's DROP SCHEMA.
--
-- THE HIDDEN TREATMENT CLONE
-- ---------------------------
-- experiment_clone_of marks a REAL `agent` row as a treatment clone. It is a
-- real row (not a parallel "shadow execution" record) so the clone can be
-- chatted with through the exact same AgentChatService pipeline as any other
-- agent - the routing decision (AgentExperimentService#resolveEffectiveAgentId)
-- is the only thing that is special about it. AgentService#listAll excludes
-- every row where this column is non-null: a clone is an implementation
-- detail, never a customer-visible agent. No ON DELETE CASCADE from the base
-- agent to its clone(s) - an experiment must be stopped (which is a normal,
-- application-level lifecycle transition) before its base agent can be
-- deleted; AgentService's existing delete-time checks are unaffected by this
-- column and continue to gate deletes through their own rules.
ALTER TABLE agent ADD COLUMN experiment_clone_of UUID REFERENCES agent(id);

-- Sparse by construction (one row per active/past treatment clone, versus
-- every agent ever created) - a partial index keeps the "exclude clones from
-- listing" query and any future clone-lookup cheap without bloating the
-- index over the overwhelming majority of rows where this is null.
CREATE INDEX idx_agent_experiment_clone_of ON agent (experiment_clone_of)
    WHERE experiment_clone_of IS NOT NULL;

CREATE TABLE agent_experiment (
    id                             UUID          PRIMARY KEY,

    -- The BASE (customer-visible) agent under test. ON DELETE CASCADE
    -- mirrors agent_version (V212): an experiment record of a deleted agent
    -- is unactionable by construction - there is nothing left to route
    -- traffic for.
    agent_id                       UUID          NOT NULL REFERENCES agent(id) ON DELETE CASCADE,

    name                           VARCHAR(255)  NOT NULL,

    -- CHECK constraint for the same reason V212/V207 constrain their own
    -- enum-backed status columns: this is read back through
    -- @Enumerated(STRING), so one typo'd value written by a hand-run SQL fix
    -- would make every read of this table throw, not just the bad row.
    status                         VARCHAR(16)   NOT NULL,

    -- The ORIGINAL (unmodified) agent that control traffic is served by.
    -- Today this always equals agent_id - a dedicated column rather than
    -- reusing agent_id directly keeps the "which two agent ids does this
    -- experiment compare" question answerable with a single row read, and
    -- leaves room for a future control-also-differs-from-base shape without
    -- another migration.
    control_agent_id               UUID          NOT NULL REFERENCES agent(id),

    -- The HIDDEN clone (agent.experiment_clone_of = agent_id) that treatment
    -- traffic is served by. No ON DELETE CASCADE: a clone is deleted only by
    -- the same application-level lifecycle that deletes any other agent, and
    -- an experiment row that outlives its clone is still meaningful history.
    treatment_agent_id             UUID          NOT NULL REFERENCES agent(id),

    -- The agent_version row the treatment clone was created FROM, for
    -- provenance ("what configuration did this experiment actually test") -
    -- nullable because agent_version rows are deleted along with their agent
    -- (ON DELETE CASCADE, V212), and an experiment must survive that.
    treatment_version_id           UUID,

    -- Treatment's share of traffic, 0-100. Re-bucketing on change never
    -- reshuffles an already-assigned subject - see
    -- agent_experiment_assignment's own comment below.
    traffic_percent                INTEGER       NOT NULL,

    -- Optional auto-stop guardrail: when set, the scheduled sweep
    -- (AgentExperimentService) stops the experiment the moment the
    -- treatment's recent error rate exceeds this percentage. NULL means no
    -- automatic guardrail - a tenant must stop manually.
    auto_stop_error_threshold_pct  INTEGER,

    -- Populated on any stop (manual or automatic) - "why did this experiment
    -- end", read by the console's history view.
    stop_reason                    TEXT,

    created_by                     VARCHAR(255),
    created_at                     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at                     TIMESTAMPTZ,
    stopped_at                     TIMESTAMPTZ,

    CONSTRAINT agent_experiment_status_chk CHECK (status IN ('DRAFT','RUNNING','STOPPED','COMPLETED')),
    CONSTRAINT agent_experiment_traffic_chk CHECK (traffic_percent BETWEEN 0 AND 100)
);

-- The list read: "every experiment ever run against this agent, newest first."
CREATE INDEX idx_agent_experiment_agent_id ON agent_experiment (agent_id, created_at DESC);

-- "Is this agent currently under test" is a single-valued question - same
-- partial-unique shape as V212's uq_agent_version_agent_id_label and V260's
-- agent_eval_gate.agent_id UNIQUE. At most one DRAFT or RUNNING row per
-- agent; STOPPED/COMPLETED rows are history and may accumulate freely.
CREATE UNIQUE INDEX uq_agent_experiment_active_per_agent ON agent_experiment (agent_id)
    WHERE status IN ('DRAFT','RUNNING');

-- The scheduled auto-stop sweep's read: every RUNNING experiment (in this
-- tenant schema) that configured a threshold. Partial because most rows are
-- never RUNNING-with-a-threshold at any given moment.
CREATE INDEX idx_agent_experiment_running_autostop ON agent_experiment (status)
    WHERE status = 'RUNNING' AND auto_stop_error_threshold_pct IS NOT NULL;

CREATE TABLE agent_experiment_assignment (
    id             UUID          PRIMARY KEY,
    experiment_id  UUID          NOT NULL REFERENCES agent_experiment(id) ON DELETE CASCADE,

    -- The bucketed identity - a chat session id in practice (see
    -- AgentExperimentService#resolveEffectiveAgentId's javadoc), opaque here.
    subject_key    VARCHAR(255)  NOT NULL,

    variant        VARCHAR(16)   NOT NULL,
    assigned_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT agent_experiment_assignment_variant_chk CHECK (variant IN ('CONTROL','TREATMENT'))
);

-- Stored-assignment-wins (the FeatureFlagService/ExperimentAssignment
-- precedent, applied here): once a subject is bucketed for THIS experiment,
-- a later traffic_percent change never reshuffles them - the unique index is
-- what makes "look up first, bucket only on miss" race-free rather than a
-- convention a concurrent request could violate.
CREATE UNIQUE INDEX uq_agent_experiment_assignment_subject ON agent_experiment_assignment (experiment_id, subject_key);
