CREATE TABLE quick_task_run (
    task_id VARCHAR(128) PRIMARY KEY,
    agent_id UUID NOT NULL,
    requested_by_keycloak_user_id VARCHAR(128) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    request_message TEXT NOT NULL,
    result_text TEXT,
    delivery_note TEXT,
    status VARCHAR(16) NOT NULL,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_quick_task_run_requested_by_started_at ON quick_task_run (requested_by_keycloak_user_id, started_at DESC);
