-- Workflow run data store (expression language, spec 4.1).
--
-- The FULL output of every node that completed in a run, so a later node's
-- {{ $node["Label"].field }} expression can read data from any earlier step.
-- Run traces are the wrong source: NodeTracePayloads redacts and truncates them.
-- These rows are deliberately un-redacted business data, therefore:
--   * output_text is capped at 256000 chars; a longer output is stored cut off
--     with truncated = true, and an expression that reads it fails loudly
--     instead of silently reading a partial value;
--   * rows are deleted with their run (cascade) and by a TTL job
--     (app.workflow.run-data.ttl-days, default 14).
--
-- seq: loop / fan_out / sub_flow bodies execute the same node many times in one
-- run, one row per iteration. Node-id "__trigger" (label "$trigger") holds the
-- run input so $trigger can resolve to it.
CREATE TABLE IF NOT EXISTS workflow_run_node_output (
    run_id       UUID         NOT NULL REFERENCES workflow_run(id) ON DELETE CASCADE,
    node_id      TEXT         NOT NULL,
    seq          INTEGER      NOT NULL DEFAULT 0,
    node_label   TEXT,
    status       TEXT,
    output_text  TEXT,
    truncated    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, node_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_workflow_run_node_output_label ON workflow_run_node_output(run_id, node_label);
CREATE INDEX IF NOT EXISTS idx_workflow_run_node_output_created ON workflow_run_node_output(created_at);
