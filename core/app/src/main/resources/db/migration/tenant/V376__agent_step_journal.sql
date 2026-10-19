-- Step journal (docs/plans/Agentic-Platform-Gap-Analysis-2026-10-01.md, Later item L1).
--
-- One row per (run_id, step_id): a durable record that a unit of agent work STARTED and, later, COMPLETED
-- with a result. It is what lets an interrupted agent job resume instead of failing as ORPHANED, what makes
-- DURABLE tool effects exactly-once across a worker restart, and what lets delegate_parallel branches that
-- already finished be skipped on a re-run.
--
--   run_id   - the agent job id (as text) for turn steps; the job chat session id ("job:<uuid>") for swarm
--              steps; the constant "effects" for tool effects. Free-form text on purpose: no FK, so deleting a
--              job never has to cascade here.
--   step_id  - "turn:<n>" | "effect:<sha256>" | "swarm:<sha256>".
--   kind     - TURN | EFFECT | SWARM (informational, for operators).
--   status   - STARTED | COMPLETED.
--
-- Everything is gated by app.agent-jobs.durability.step-journal-enabled (default false); with the flag off
-- nothing reads or writes this table. Single-replica assumption: see docs/plans/adr/step-journal-resumable-jobs.md.
--
-- VERSION NUMBER: V376 reserved for this workstream (V375 = eval credibility was the highest in tenant/).
CREATE TABLE IF NOT EXISTS agent_step_journal (
    id           UUID         PRIMARY KEY,
    run_id       VARCHAR(128) NOT NULL,
    step_id      VARCHAR(200) NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    result_text  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_agent_step_journal_run_step UNIQUE (run_id, step_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_step_journal_created ON agent_step_journal (created_at);
