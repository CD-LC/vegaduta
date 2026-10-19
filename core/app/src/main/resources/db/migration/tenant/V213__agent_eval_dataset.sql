-- Eval datasets + regression runs (offline evaluation, workstream "Eval
-- datasets / regression runs / activate gate") - the missing OFFLINE half of
-- core/quality. The six LLM-as-judge evaluators (V152's agent_quality_record
-- was built for their ONLINE scoring path, which nothing ever wired - see
-- QualityEvaluationService#evaluateResponseAsync's callers: there are none)
-- get their first real consumer here: a tenant-authored set of golden cases
-- that can be replayed against an agent on demand, scored per-case, and
-- compared run-over-run so a prompt/model/tool change has a before/after
-- number instead of a vibe.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream, so a number is free only when it is free in BOTH folders
-- (see V207/V175/V155 for the same paragraph - and docs/ZERO-DOWNTIME-DEPLOY-
-- RULES.md for the 2026-07-31 production outage a duplicate V156 caused; a
-- collision here is an OUTAGE, not a merge conflict). Checked at authoring
-- time (2026-08-18): tenant/'s highest is V215 (V213/V214 absent - V212 and
-- V215 are claimed by the concurrently-running agent-version and per-user-
-- credential workstreams), controlplane/'s highest is V216. V213 is the slot
-- assigned to THIS workstream and is free in both folders; V214 belongs to
-- the handoff workstream and V217 is the next genuinely unclaimed number.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- Same argument as V207 (agent_job), and it is load-bearing here too: dataset
-- cases are CUSTOMER-AUTHORED prompts and run results carry raw MODEL OUTPUT.
-- Both must vanish with TenantDataErasureService's DROP SCHEMA - a control-
-- plane placement would be a right-to-erasure defect, not a design preference.
-- (V152 got this wrong: it sits in controlplane/ UNQUALIFIED, so
-- agent_quality_record landed in `public` where no tenant connection's
-- search_path can even see it. These tables deliberately do NOT repeat that:
-- they are tenant/-scoped and schema-unqualified, replayed into each tenant
-- schema by TenantProvisioningService like every other tenant migration.)
--
-- WHY agent_id / dataset case content ARE NOT FOREIGN-KEYED TO agent
-- ------------------------------------------------------------------
-- Same choice as agent_job.agent_id, same reasoning: an FK would turn "delete
-- an agent that once had a dataset" into a constraint-violation 500. A run
-- whose agent has vanished fails at replay time with a real, visible error
-- message instead.

CREATE TABLE eval_dataset (
    id                      UUID          PRIMARY KEY,

    -- The agent this dataset regression-tests. Deliberately no FK - see header.
    agent_id                UUID          NOT NULL,

    name                    VARCHAR(255)  NOT NULL,
    description             TEXT,

    -- The pass bar a run's aggregate score is judged against, snapshot onto
    -- each eval_run at trigger time (see eval_run.pass_threshold) so editing
    -- the bar never retroactively changes what a historical run "passed".
    -- NUMERIC(4,3) holds 0.000-1.000, same [0,1] scale as every judge score.
    pass_threshold          NUMERIC(4,3)  NOT NULL DEFAULT 0.700,

    -- Replays run TOOL-LESS by default (AgentChatService#chatTextOnly), for
    -- the same reason that method exists at all: a replayed case routed
    -- through the full pipeline re-executes REAL side-effecting tools
    -- (send_channel_message, generate_document, any mcp_tool.*) - an offline
    -- eval that sends actual WhatsApp messages is an incident, not a test
    -- run. Datasets that assert on tool usage opt in explicitly, eyes open.
    tool_execution_enabled  BOOLEAN       NOT NULL DEFAULT FALSE,

    -- Keycloak subject of the author. Opaque VARCHAR, never FK'd - same
    -- reasoning as user_permission_grant.keycloak_user_id (V199).
    created_by              VARCHAR(255),

    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_dataset_agent ON eval_dataset (agent_id, created_at DESC);

CREATE TABLE eval_dataset_case (
    id               UUID          PRIMARY KEY,

    -- A case is pure composition - it has no meaning outside its dataset, so
    -- unlike agent_id above this IS a foreign key, and deleting a dataset
    -- takes its cases with it. (Contrast with eval_run below, which also
    -- cascades but only because EvalDatasetService refuses to delete a
    -- dataset with a live run - see its javadoc.)
    dataset_id       UUID          NOT NULL REFERENCES eval_dataset (id) ON DELETE CASCADE,

    -- The user turn to replay.
    user_message     TEXT          NOT NULL,

    -- Optional reference answer. When present it is handed to the ACCURACY
    -- judge as ground truth (EvaluationContext#expectedOutput ->
    -- AccuracyEvaluator's REFERENCE ANSWER block) - it does not gate anything
    -- deterministically on its own.
    expected_output  TEXT,

    -- Optional JSON array of tool names (e.g. '["search_knowledge_base"]')
    -- the agent is expected to have called during this turn. TEXT not JSONB:
    -- nothing queries INTO it (same choice as agent_job.result_json). Only
    -- assertable when the dataset opted into tool execution - a tool-less
    -- replay reports the assertion as not-evaluated, never as failed.
    expected_tools   TEXT,

    -- Stable display/replay order within the dataset.
    position         INTEGER       NOT NULL DEFAULT 0,

    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_dataset_case_dataset ON eval_dataset_case (dataset_id, position);

CREATE TABLE eval_run (
    id                     UUID          PRIMARY KEY,

    dataset_id             UUID          NOT NULL REFERENCES eval_dataset (id) ON DELETE CASCADE,

    -- Snapshot of the dataset's agent at trigger time (the dataset row could
    -- be re-pointed later; the run must record what it actually tested).
    agent_id               UUID          NOT NULL,

    -- Redundant with the schema this row lives in, on purpose - the same
    -- choice agent_job (V207) made, for the same reason: the executor worker
    -- thread and the boot orphan sweep rebuild TenantContext FROM THIS ROW
    -- (or its captured copy) before their first repository read.
    tenant_id              VARCHAR(255)  NOT NULL,

    -- IDENTITY, SNAPSHOTTED AT TRIGGER - same contract as agent_job. The
    -- replayed turns are metered against requested_by as the
    -- PerUserBudgetService subject, so a run can never become a way to spend
    -- money no human owns (AgentJobService#submit documents the escape hatch
    -- a synthetic subject would open). requested_by_roles is a comma-joined
    -- snapshot used to rebuild TenantContext on the worker thread.
    requested_by           VARCHAR(255)  NOT NULL,
    requested_by_roles     VARCHAR(512),

    status                 VARCHAR(16)   NOT NULL,

    -- Null until the run reaches SUCCEEDED and the verdict is computable;
    -- see EvalRunService#computeVerdict for the three-way rules (UNAVAILABLE
    -- judge results are INCONCLUSIVE, never a regression to 0 - the same
    -- honest-absence contract as EvaluationResult itself, and the same
    -- reasoning TokenBaseline documents for its own refusal to fabricate).
    verdict                VARCHAR(16),

    -- The dataset's pass bar frozen at trigger time - editing the dataset's
    -- threshold must never retroactively flip a historical verdict.
    pass_threshold         NUMERIC(4,3)  NOT NULL,

    -- Mean of the per-case aggregate scores that were actually measured;
    -- null when nothing was measured (honest absence, never a made-up 0.5).
    overall_score          NUMERIC(4,3),

    case_count             INTEGER       NOT NULL DEFAULT 0,
    scored_case_count      INTEGER       NOT NULL DEFAULT 0,
    -- Cases whose expected-tools assertion evaluated to FALSE (not counting
    -- not-evaluated ones) plus cases that errored - the "hard failure" count
    -- the verdict rules read.
    failed_assertion_count INTEGER       NOT NULL DEFAULT 0,
    errored_case_count     INTEGER       NOT NULL DEFAULT 0,

    -- Provenance: the judge model JudgeChatClientFactory resolved when the
    -- run started. Recorded because the factory picks models.get(0) from the
    -- provider catalog - a catalog reorder silently changes the judge, and a
    -- run-over-run diff scored by two different judges is not a diff. Storing
    -- it makes that drift VISIBLE rather than pretending it cannot happen.
    judge_model            VARCHAR(255),

    error_message          TEXT,

    -- Cooperative cancel is a COLUMN, not a status write - copied verbatim
    -- from agent_job's reasoning: it keeps the cancel writer entirely off
    -- `status`, so only the owning worker ever writes status for a RUNNING
    -- run, and no @Version/optimistic-lock retry is needed anywhere.
    cancel_requested       BOOLEAN       NOT NULL DEFAULT FALSE,
    cancel_requested_by    VARCHAR(255),

    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,

    -- Same insurance as agent_job_status_chk: status/verdict are read back
    -- through @Enumerated(STRING), so one typo'd value written by a hand-run
    -- SQL fix makes every findAll() throw for the whole table.
    CONSTRAINT eval_run_status_chk  CHECK (status IN
        ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT eval_run_verdict_chk CHECK (verdict IS NULL OR verdict IN
        ('PASSED','FAILED','INCONCLUSIVE'))
);

CREATE INDEX idx_eval_run_dataset_created ON eval_run (dataset_id, created_at DESC);

-- At most ONE live run per dataset, enforced by the database rather than by a
-- check-then-act in the service (which two concurrent POSTs would race
-- straight through). The second trigger's INSERT fails cleanly and surfaces
-- as a 409. Partial-index caveat, reproduced honestly from V211: Hibernate
-- binds status as a parameter, so Postgres only proves the predicate under a
-- custom plan - fine for an INSERT-uniqueness guard, which is evaluated on
-- write with literal values.
CREATE UNIQUE INDEX uq_eval_run_live_per_dataset ON eval_run (dataset_id)
    WHERE status IN ('QUEUED','RUNNING');

CREATE TABLE eval_run_case_result (
    id                       UUID          PRIMARY KEY,

    run_id                   UUID          NOT NULL REFERENCES eval_run (id) ON DELETE CASCADE,

    -- The originating case, informational only - deliberately NOT an FK and
    -- nullable in spirit (the case may be edited or deleted after the run).
    -- The snapshot columns below make this row fully self-contained, so a
    -- historical run remains readable and re-explainable forever regardless
    -- of what happened to the dataset afterwards.
    case_id                  UUID,
    position                 INTEGER       NOT NULL DEFAULT 0,

    -- SNAPSHOTS of the case at run time, plus what actually happened.
    user_message             TEXT          NOT NULL,
    expected_output          TEXT,
    expected_tools           TEXT,
    agent_response           TEXT,
    -- JSON array of {toolName, input, output} from the replayed turn (only
    -- populated for tool-executing datasets). TEXT not JSONB - read back
    -- whole, never queried into.
    tool_calls_json          TEXT,

    -- Per-dimension judge results as JSON: {"ACCURACY": {"score": 0.75,
    -- "status": "EVALUATED", "feedback": "..."}, ...}. Kept as one document
    -- rather than the @ElementCollection side-tables agent_quality_record
    -- uses because nothing aggregates ACROSS rows per-dimension here (the
    -- run-level aggregate is precomputed onto eval_run) and one column keeps
    -- the result row cheap to write per case inside the run loop.
    scores_json              TEXT,

    -- The case-level weighted aggregate from EvalEngine, null when no judge
    -- produced a real measurement for this case.
    overall_score            NUMERIC(4,3),

    -- Three-valued on purpose: TRUE (asserted and satisfied), FALSE (asserted
    -- and violated), NULL (no assertion, or tool-less replay where the
    -- assertion is unevaluable - which must never read as a failure).
    expected_tools_satisfied BOOLEAN,

    status                   VARCHAR(16)   NOT NULL,
    error_message            TEXT,

    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at              TIMESTAMPTZ,

    CONSTRAINT eval_run_case_result_status_chk CHECK (status IN
        ('SCORED','UNAVAILABLE','ERROR'))
);

CREATE INDEX idx_eval_run_case_result_run ON eval_run_case_result (run_id, position);
