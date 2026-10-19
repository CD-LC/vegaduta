-- Workflow data pinning and draft runs (docs/plans/WORKFLOW-EXPRESSIONS-AND-PINNING-SPEC.md, section 5).
--
-- workflow_node_pin: a frozen output attached to a node on the DRAFT graph. Only draft/test runs read it
-- (the engine decides by the run's mode, never by anything a client sends), so a stray pin cannot change
-- what a webhook, cron, channel, MCP or share-link run does.
--
-- Pins can hold PII from real runs: they are draft-scope, not part of version snapshots, and never copied
-- by duplicate / share / marketplace. Limits (enforced in WorkflowPinService, not here): 256000 chars per
-- pin, 30 pins and 2000000 chars per workflow. size_chars is stored so the per-workflow total is a cheap
-- SUM without reading every payload.
--
-- node_id has no foreign key: nodes live inside workflow_definition.graph_json, not in a table. The
-- service deletes a pin when a graph save removes its node; the cascade below covers deleting the workflow.
CREATE TABLE IF NOT EXISTS workflow_node_pin (
    workflow_id    UUID          NOT NULL REFERENCES workflow_definition(id) ON DELETE CASCADE,
    node_id        TEXT          NOT NULL,
    data_text      TEXT          NOT NULL,
    size_chars     INTEGER       NOT NULL,
    source         VARCHAR(32)   NOT NULL,
    source_run_id  VARCHAR(64),
    pinned_by      VARCHAR(200),
    pinned_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    config_hash    VARCHAR(128),
    PRIMARY KEY (workflow_id, node_id)
);

-- The mode a run was started in (PRODUCTION / DRAFT / TEST). Persisted on the row, not just passed down,
-- so a run that parks for approval or pause and resumes later keeps honouring (or ignoring) pins exactly
-- as it started, and the console can label a draft run. Existing rows are production runs.
ALTER TABLE workflow_run ADD COLUMN IF NOT EXISTS run_mode VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION';
