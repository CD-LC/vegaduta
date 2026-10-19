-- Autonomous Agents PR-1 (FOUNDATION) - the agent JOB primitive: a unit of
-- agent work that outlives the HTTP request that asked for it.
--
-- Before this table the platform had no way for an agent to do anything that
-- was not a single, synchronous chat turn: `git grep` for ScheduledAgent /
-- AgentSchedule / BackgroundTask returns zero hits, and every agent run is a
-- Flux subscribed by (and dying with) the servlet request. Only WORKFLOWS had
-- schedules, webhooks and durable execution. This table is what brings those
-- properties down to the AGENT level.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream, so a number is free only when it is free in BOTH folders
-- (see V175/V155/V145 for the same paragraph - and docs/ZERO-DOWNTIME-DEPLOY-
-- RULES.md for the 2026-07-31 production outage a duplicate V156 caused; a
-- collision here is an OUTAGE, not a merge conflict). Checked at authoring
-- time: tenant/'s highest is V205, controlplane/'s highest is V206, so V207 is
-- the next slot free in both. V208 (agent_schedule) and V209 (agent job output
-- + progress) are claimed by the sibling PRs of this same workstream; V210 and
-- V211 are RESERVED for the deferred event-trigger and approval-parking PRs.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- `agent`, `tool_call_log`, `workflow_run`, `approval_request` and
-- `audit_log_entry` are all tenant-scoped. A control-plane agent_job would (a)
-- make every job trace a cross-schema join, (b) survive TenantDataErasureService's
-- DROP SCHEMA still holding customer prompts and model output - a right-to-erasure
-- defect, not a design preference - and (c) be unable to reference `agent` at all.
-- Operator "show me every tenant's jobs" views are answered the way this repo
-- already answers them: TenantSchemaIterator fan-out.
--
-- WHY THIS MIGRATION CARRIES COLUMNS NOTHING READS YET
-- ----------------------------------------------------
-- schedule_id/schedule_occurrence_at, trigger_id/trigger_depth and the four
-- park_* columns are written by later PRs, not this one. They are here now on
-- purpose. WorkflowRunRecoveryJob's javadoc records that it deliberately
-- designed AROUND needing a column, because taking a number out of this
-- contended shared namespace is itself the risk being managed. One migration
-- that anticipates four known-shaped follow-ups is strictly safer than four
-- migrations racing each other for numbers.

CREATE TABLE agent_job (
    id                          UUID          PRIMARY KEY,

    -- Redundant with the schema this row lives in, on purpose - the same
    -- choice document_generation_job made, for the same reason: the poller and
    -- the boot sweep rebuild TenantContext FROM THIS ROW before their first
    -- repository read, and a row that cannot name its own tenant cannot be
    -- resumed by a worker thread that has no context yet.
    tenant_id                   VARCHAR(255)  NOT NULL,

    -- Deliberately NOT a foreign key to agent(id) - matching workflow_run's
    -- workflow_id rather than subagent_run's agent_id. Run history must outlive
    -- the definition, and an FK would turn "delete an agent that once ran a
    -- job" into a constraint-violation 500 instead of the clean
    -- AgentInUseException 409 the console already renders. A job whose agent
    -- has vanished fails at claim time with error_code='AGENT_NOT_FOUND': a
    -- real, visible outcome rather than a hidden constraint.
    agent_id                    UUID          NOT NULL,

    goal                        TEXT          NOT NULL,
    status                      VARCHAR(32)   NOT NULL,

    -- IDENTITY, SNAPSHOTTED AT ENQUEUE.
    -- subject_key is PerUserBudgetService's userKey and is NOT NULL precisely
    -- so a job can never become a way to spend money no human owns.
    -- requested_by_roles is a comma-joined snapshot used to rebuild
    -- TenantContext on the worker: a job runs with the REQUESTER'S authority
    -- frozen at submit time, never elevated system authority, so it can never
    -- do more than the human who asked for it. Staleness of that snapshot is
    -- bounded by app.agent-jobs.queue-ttl.
    requested_by                VARCHAR(255)  NOT NULL,
    requested_by_roles          VARCHAR(512),
    subject_key                 VARCHAR(255)  NOT NULL,

    origin                      VARCHAR(32)   NOT NULL,   -- API | SCHEDULE | EVENT
    origin_ref                  VARCHAR(255),

    -- "job:<uuid>" (see AgentJobSession). 40 of the 128 chars tool_call_log's
    -- own session_id column allows. This is the join key that makes
    -- tool_call_log / llm_call_log / the Langfuse session id / rl_episode
    -- attribution all work for jobs with ZERO new plumbing: everything
    -- downstream already keys on the chat turn's sessionId.
    session_id                  VARCHAR(128)  NOT NULL,

    -- Latest note only, denormalized so an operator list can render "what is
    -- this doing right now" for 50 jobs without an N+1. The full history lives
    -- in agent_job_progress (V209).
    progress_note               VARCHAR(500),
    progress_updated_at         TIMESTAMPTZ,

    result_text                 TEXT,
    -- TEXT, not JSONB, matching workflow_run.trace_json: nothing queries INTO
    -- this document, it is only ever read back whole and handed to the client.
    result_json                 TEXT,
    -- The schema SNAPSHOT taken at enqueue, so editing an agent's result schema
    -- mid-flight cannot retroactively invalidate a job already running under
    -- the old one.
    result_schema_json          TEXT,
    schema_attempts             INTEGER       NOT NULL DEFAULT 0,
    cost_usd                    NUMERIC(12,6),

    error_code                  VARCHAR(64),
    error_message               TEXT,

    -- deadline_at is stamped at CLAIM, not at enqueue, so a job that sat in the
    -- queue behind others still gets its full execution budget. Stored rather
    -- than computed because the deadline sweep queries on it and an expression
    -- is not indexable.
    timeout_seconds             INTEGER       NOT NULL,
    deadline_at                 TIMESTAMPTZ,
    max_turns                   INTEGER       NOT NULL DEFAULT 1,
    turns_used                  INTEGER       NOT NULL DEFAULT 0,
    max_tool_calls              INTEGER       NOT NULL,

    -- Cooperative cancel is a COLUMN here, not the Redis flag
    -- WorkflowRunCancellationService uses. That service is documented fail-open
    -- with a 1h TTL - a fine trade for a run that finishes in seconds, and the
    -- wrong one for a job that may be parked for hours awaiting approval. It
    -- also keeps the cancel writer entirely off `status`, so only the owning
    -- worker ever writes status for a RUNNING job, which is why this table needs
    -- no @Version column and no optimistic-lock retry anywhere.
    cancel_requested            BOOLEAN       NOT NULL DEFAULT FALSE,
    cancel_requested_by         VARCHAR(255),
    cancel_requested_at         TIMESTAMPTZ,

    -- owner_instance is host+pid so a boot sweep on replica A can never reap a
    -- job replica B is genuinely still running. heartbeat_at is an EXPLICIT
    -- liveness signal because, unlike workflow_run, an agent job has no per-node
    -- trace to derive staleness from and can legitimately sit inside ONE LLM
    -- call for minutes without emitting anything.
    owner_instance              VARCHAR(128),
    heartbeat_at                TIMESTAMPTZ,
    attempt_count               INTEGER       NOT NULL DEFAULT 0,

    -- Written by the SCHEDULES PR (V208 adds its guard indexes on these).
    schedule_id                 UUID,
    schedule_occurrence_at      TIMESTAMPTZ,
    -- Written by the deferred event-trigger PR. Present now so it needs no ALTER.
    trigger_id                  UUID,
    trigger_depth               INTEGER       NOT NULL DEFAULT 0,
    -- Written by the deferred approval-parking PR. Present now for the same reason.
    parked_at                   TIMESTAMPTZ,
    approval_deadline_at        TIMESTAMPTZ,
    park_count                  INTEGER       NOT NULL DEFAULT 0,
    resume_reason               TEXT,

    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at                  TIMESTAMPTZ,
    finished_at                 TIMESTAMPTZ,

    -- workflow_run has no equivalent constraint; this table does, deliberately.
    -- status is read back through @Enumerated(STRING), so ONE typo'd value
    -- written by a hand-run SQL fix makes every findAll() throw for the whole
    -- table, not just the bad row. Cheap insurance against a class of incident
    -- that is very hard to diagnose from the resulting stack trace.
    CONSTRAINT agent_job_status_chk CHECK (status IN
        ('QUEUED','RUNNING','AWAITING_APPROVAL','SUCCEEDED','FAILED','CANCELLED'))
);

-- Serves BOTH hot reads: the dispatcher's "status='QUEUED' ORDER BY created_at
-- LIMIT n" and the operator list filtered by status. A btree index scans in
-- either direction, so no separate DESC variant is needed.
CREATE INDEX idx_agent_job_status_created ON agent_job (status, created_at);

CREATE INDEX idx_agent_job_agent_created  ON agent_job (agent_id, created_at DESC);

-- The deadline and heartbeat sweeps only ever look at live rows, and live rows
-- are a vanishing fraction of the table once a tenant has any history.
CREATE INDEX idx_agent_job_live ON agent_job (deadline_at)
    WHERE status IN ('RUNNING','AWAITING_APPROVAL');
