-- Autonomous Agents PR-6 (OBSERVABILITY) - the two things that make a job that
-- runs for ten minutes actually USABLE by something other than a human reading
-- prose:
--   (a) an optional JSON Schema the result must conform to, so a caller gets a
--       machine-readable object or an explicit failure - never prose it then has
--       to guess its way through;
--   (b) a progress trail the running job publishes, so a long job is visibly
--       alive rather than indistinguishable from a hung one.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream, so a number is free only when it is free in BOTH folders (see
-- V207/V175/V155 for the same paragraph, and docs/ZERO-DOWNTIME-DEPLOY-RULES.md
-- for the 2026-07-31 production outage a duplicate V156 caused - a collision
-- here is an OUTAGE, not a merge conflict). Checked at authoring time, in both
-- directories: tenant/ holds V205, V207 (agent_job, this workstream's
-- FOUNDATION) and V208 (agent_schedule, this workstream's SCHEDULES half);
-- controlplane/ holds V206 as its highest. V209 is therefore the next slot free
-- in both. V210 and V211 are RESERVED for the deferred event-trigger and
-- approval-parking PRs and must NOT be taken here.
--
-- WHY agent.result_schema_json IS A NEW COLUMN AND NOT agent.output_schema_json
-- -----------------------------------------------------------------------------
-- output_schema_json already exists (V170, workflow -> agent structured
-- parameter passing) and looks like the obvious home for this. It is not, and
-- reusing it would be a silent data-loss bug rather than a style preference:
-- AgentSchemaService#refresh REGENERATES input_schema_json/output_schema_json
-- from the agent's tools, knowledge scope and model on EVERY AgentService save
-- that touches any of those (see Agent#setGeneratedSchema, which also bumps
-- schema_version). A tenant-authored result schema parked in that column would
-- be destroyed the next time anyone edited the agent's tool list - and destroyed
-- silently, with the job that depended on it failing later for a reason nobody
-- would connect back to the edit. The two schemas answer different questions:
-- output_schema_json is "what shape does this agent hand back to a workflow
-- node", derived by the platform; result_schema_json is "what shape did a HUMAN
-- say this agent's job results must take", authored by the tenant and owned by
-- them.
ALTER TABLE agent ADD COLUMN IF NOT EXISTS result_schema_json TEXT;

-- The per-job HISTORY of progress notes. agent_job.progress_note (V207) already
-- carries the LATEST note, denormalized, so the operator list can render "what
-- is this doing right now" for fifty jobs in one query; this table is what makes
-- "what has it been doing for the last eight minutes" answerable. Both exist on
-- purpose - the denormalized column exists to avoid an N+1 on the list screen,
-- this table exists because a single latest note is not a trace.
CREATE TABLE agent_job_progress (
    id          UUID          PRIMARY KEY,

    -- A real FK, unlike agent_job.agent_id. The reasoning that made THAT column
    -- deliberately unconstrained (run history must outlive the agent definition)
    -- does not apply here in either direction: a progress note has no meaning
    -- whatsoever without the job it describes, and ON DELETE CASCADE is exactly
    -- the behavior wanted when a tenant's job history is pruned or their schema
    -- is dropped by TenantDataErasureService.
    job_id      UUID          NOT NULL REFERENCES agent_job(id) ON DELETE CASCADE,

    -- Length-matched to agent_job.progress_note, because every note written here
    -- is ALSO copied to that column. A wider column here would mean the two
    -- surfaces disagreed about the same note, with the list view showing a
    -- silently different (truncated) string from the trace view.
    note        VARCHAR(500)  NOT NULL,

    -- NULLABLE, and null is the common case. Only populated when the agent
    -- actually supplied a percentage; the runtime never invents one. A synthetic
    -- "we're about 40% through" derived from turn count would be a number that
    -- looks authoritative and is not, which is worse for an operator deciding
    -- whether to cancel than having no number at all.
    percent     SMALLINT,

    -- AGENT (the model called report_progress) | RUNTIME (the platform noted a
    -- lifecycle event). Kept distinct because the trust level differs: a RUNTIME
    -- note is a fact, an AGENT note is a claim the model made about itself.
    source      VARCHAR(16)   NOT NULL,

    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Serves both reads this table has: the full trace for one job, and the
-- incremental "?after=<instant>" poll the live trace stream issues once a
-- second. Same column order works for both because the poll is a range scan on
-- the tail of the same ordering.
CREATE INDEX idx_agent_job_progress_job ON agent_job_progress (job_id, created_at);

-- Same posture as agent_job_status_chk in V207, and for the same reason: source
-- is read back through @Enumerated(STRING), so ONE typo'd value written by a
-- hand-run SQL fix makes every read of this table throw for the whole job, not
-- just for the bad row.
ALTER TABLE agent_job_progress ADD CONSTRAINT agent_job_progress_source_chk
    CHECK (source IN ('AGENT', 'RUNTIME'));

-- NO seq/ordinal column, deliberately. Spring AI can dispatch tool calls in
-- PARALLEL within a turn, so a MAX(seq)+1 computed on the writer would be racy
-- and there is no per-job sequence generator to take one from instead. The
-- canonical order is (created_at ASC, id ASC) - id breaking ties keeps the
-- ordering total and therefore keeps the "?after=<instant>" cursor from either
-- skipping or repeating a note when two land in the same microsecond.
