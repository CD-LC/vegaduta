-- Workflow effect journal: durable, exactly-once bookkeeping for the irreversible
-- nodes of the in-process workflow engine (bulk_email, tool, crm_lead,
-- device_control, media, output, workflow, non-GET http).
--
-- Workflow execution is at-least-once: a node retry, a Temporal activity retry,
-- an approval resume or a re-run after a crash can reach the same
-- (run, node, iteration) twice, and none of those side effects is idempotent on
-- its own. The engine writes a STARTED row BEFORE the node's side effect and
-- flips it to COMPLETED (with the recorded result) AFTER it:
--   * a second execution of a COMPLETED key replays the recorded result and does
--     not run the effect again;
--   * a STARTED row with no COMPLETED is "effect uncertain" (the process died
--     somewhere between the call and the bookkeeping) and is never silently
--     re-run - app.workflow.effects.on-uncertain decides fail (default) or rerun;
--   * FAILED means the handler threw before reporting success; a retry may claim
--     the key again (attempt_count + 1).
-- Status values: STARTED, COMPLETED, FAILED, UNCERTAIN (application-enforced, kept
-- as VARCHAR so the set can grow without a migration).
--
-- TENANT-schema table (no schema qualifier, resolved via search_path, same
-- convention as workflow_run_node_output). Deliberately NOT a foreign key to
-- workflow_run(id), matching agent_job: the journal row must be writable before
-- anything else about the run is guaranteed, and it is purged by age instead.
--
-- Version V680: the V680-V689 range is reserved for this feature; Flyway versions
-- are ONE namespace across controlplane/ and tenant/.
CREATE TABLE IF NOT EXISTS workflow_effect_journal (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id           UUID         NOT NULL,
    node_id          VARCHAR(200) NOT NULL,
    node_type        VARCHAR(64)  NOT NULL,
    -- Loop / fan-out / sub-flow item index; '-' for a node outside any iteration.
    iteration_key    VARCHAR(64)  NOT NULL,
    -- Attempt-independent key runId:nodeId:iteration, handed to external APIs
    -- (e.g. the HTTP node's Idempotency-Key header).
    idempotency_key  VARCHAR(512) NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    attempt_count    INT          NOT NULL DEFAULT 1,
    -- Recorded result of the completed effect (replayed instead of re-running).
    result_status    VARCHAR(32),
    result_output    TEXT,
    result_output_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    result_digest    VARCHAR(64),
    result_branch    VARCHAR(200),
    result_summary   TEXT,
    result_file_name VARCHAR(255),
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT uq_workflow_effect_journal UNIQUE (run_id, node_id, iteration_key)
);

CREATE INDEX IF NOT EXISTS idx_workflow_effect_journal_run
    ON workflow_effect_journal (run_id, status);
CREATE INDEX IF NOT EXISTS idx_workflow_effect_journal_started
    ON workflow_effect_journal (started_at);
