-- Wait workflow node: a durable timer / external-resume gate. When a run reaches a Wait node the engine parks
-- the run as PAUSED (the same resume envelope operator Pause and approvals use: workflow_run.paused_node_id /
-- paused_node_input, V261) and writes ONE row here carrying what the pause cannot: when to wake the run, in
-- which mode, and whether a webhook-mode timeout fails the run. Nothing lives in memory - a scheduled sweep
-- (WorkflowWaitWakeJob) resumes every row whose wake_at has passed, and the signed resume URL resolves a
-- webhook-mode row early. Statuses: WAITING (live), RESUMED (woken by the sweep or the URL), CANCELLED (the run
-- was cancelled / resumed by an operator / is no longer parked at this checkpoint).
--
-- Tenant-schema table, same convention as workflow_run / workflow_input_request. Version V434: the number
-- assigned to the wfcontrol lane (wave b); the shared tenant+controlplane namespace's disk max was V425 at
-- authoring time.

CREATE TABLE IF NOT EXISTS workflow_wait (
    id              UUID PRIMARY KEY,
    run_id          UUID         NOT NULL,
    workflow_id     UUID         NOT NULL,
    node_id         VARCHAR(100) NOT NULL,
    resume_node_id  VARCHAR(100) NOT NULL,
    mode            VARCHAR(16)  NOT NULL,
    wake_at         TIMESTAMPTZ  NOT NULL,
    fail_on_timeout BOOLEAN      NOT NULL DEFAULT TRUE,
    status          VARCHAR(16)  NOT NULL DEFAULT 'WAITING',
    created_at      TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ
);

-- The sweep's lookup: live rows by wake time. Partial - resolved rows are never read this way.
CREATE INDEX IF NOT EXISTS idx_workflow_wait_due ON workflow_wait (wake_at) WHERE status = 'WAITING';

-- At most one live wait per run (a run is parked at exactly one Wait node at a time).
CREATE UNIQUE INDEX IF NOT EXISTS uq_workflow_wait_run_live ON workflow_wait (run_id) WHERE status = 'WAITING';

CREATE INDEX IF NOT EXISTS idx_workflow_wait_run ON workflow_wait (run_id);
