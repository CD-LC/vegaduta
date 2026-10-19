-- Human-input workflow node (Tier-3 gap): a run can now pause to COLLECT
-- structured data mid-run, not just approve/reject. workflow_input_request
-- is the paused run's pending form: the node's form schema is snapshotted
-- at halt time (form_schema), the upstream output is carried verbatim as
-- the resume envelope (node_input - nothing else persists it), and the
-- submitted values land in submitted_values on resolution. The resumed
-- node's output becomes {"form": {...values...}, "input": <node_input>}.
--
-- Tenant-schema table (same convention as workflow_run).
-- Version V271: this session's V270-V289 range per docs/AGENT-COORDINATION.md.

CREATE TABLE IF NOT EXISTS workflow_input_request (
    id               UUID PRIMARY KEY,
    run_id           UUID         NOT NULL,
    workflow_id      UUID         NOT NULL,
    workflow_name    VARCHAR(255),
    node_id          VARCHAR(100) NOT NULL,
    node_label       VARCHAR(255),
    form_schema      JSONB,
    node_input       TEXT,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    requested_by     VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL,
    submitted_values JSONB,
    submitted_by     VARCHAR(255),
    submitted_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_workflow_input_request_status ON workflow_input_request (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_input_request_run ON workflow_input_request (run_id);
