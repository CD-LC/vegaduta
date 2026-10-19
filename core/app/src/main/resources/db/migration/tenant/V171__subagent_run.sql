CREATE TABLE IF NOT EXISTS subagent_run (
    id UUID PRIMARY KEY,
    workflow_run_id UUID NOT NULL REFERENCES workflow_run(id),
    node_id VARCHAR(128) NOT NULL,
    agent_id UUID NOT NULL REFERENCES agent(id),
    input_json TEXT,
    output_json TEXT,
    status VARCHAR(32) NOT NULL,
    latency_ms BIGINT,
    cost_usd NUMERIC(12, 6),
    error_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_subagent_run_workflow_run_id ON subagent_run(workflow_run_id);
