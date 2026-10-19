-- Schedule trigger (#166) - the other half of "how do customers trigger it"
-- alongside the webhook: a standard cron expression (interpreted in UTC) plus
-- a pre-computed next-fire time so WorkflowScheduler's per-minute tick can
-- find due workflows with a plain indexed comparison.
ALTER TABLE workflow_definition ADD COLUMN schedule_cron VARCHAR(64);
ALTER TABLE workflow_definition ADD COLUMN schedule_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workflow_definition ADD COLUMN next_run_at TIMESTAMPTZ;
CREATE INDEX idx_workflow_definition_due_schedule ON workflow_definition (schedule_enabled, next_run_at)
    WHERE schedule_enabled = TRUE;
