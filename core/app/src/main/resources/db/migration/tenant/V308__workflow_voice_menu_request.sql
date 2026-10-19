-- Voice Menu workflow node (call-logic/DTMF fast-follow): a live-call run
-- can now pause mid-graph to play an IVR-style prompt and collect DTMF
-- digits, not just the one blanket <Gather input="speech"> the CALL trigger
-- rendered before this node type existed. workflow_voice_menu_request is
-- the paused run's pending gather - the node's prompt/digit-count/timeout/
-- retry/branch config is snapshotted at halt time (prompt_text through
-- branches), the upstream output is carried verbatim as the resume envelope
-- (node_input - nothing else persists it), and the collected digits plus the
-- matched branch land in collected_digits/resolved_branch on resolution. The
-- resumed node's branch() becomes resolved_branch, routing the graph exactly
-- like Condition's true/false or Supervisor's named routes.
--
-- Same shape as workflow_input_request (V271), deliberately a SEPARATE table
-- rather than reused: the two node types resolve on different triggers (a
-- console form submission vs. a Twilio webhook), have different config
-- shapes, and the human-input console inbox has no business listing a
-- caller's in-progress IVR menu.
--
-- Tenant-schema table (same convention as workflow_input_request/workflow_run).
-- Version V308: this task's V308-V309 range per docs/AGENT-COORDINATION.md
-- (2026-09-23 gap-radar build-out fan-out).

CREATE TABLE IF NOT EXISTS workflow_voice_menu_request (
    id                  UUID PRIMARY KEY,
    run_id              UUID         NOT NULL,
    workflow_id         UUID         NOT NULL,
    workflow_name       VARCHAR(255),
    node_id             VARCHAR(100) NOT NULL,
    node_label          VARCHAR(255),
    prompt_text         TEXT,
    invalid_prompt_text TEXT,
    num_digits          INTEGER,
    finish_on_key       VARCHAR(4),
    timeout_seconds     INTEGER      NOT NULL DEFAULT 5,
    max_retries         INTEGER      NOT NULL DEFAULT 2,
    attempt             INTEGER      NOT NULL DEFAULT 1,
    branches            JSONB,
    node_input          TEXT,
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    requested_by        VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL,
    collected_digits    VARCHAR(64),
    resolved_branch     VARCHAR(64),
    resolved_at         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_workflow_voice_menu_request_status ON workflow_voice_menu_request (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_voice_menu_request_run ON workflow_voice_menu_request (run_id, status);
