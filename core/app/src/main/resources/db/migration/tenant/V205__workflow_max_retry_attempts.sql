-- Per-workflow retry-count configuration ("0 retries" vs "5 retries" per
-- workflow, tenant-configurable via PATCH /api/workflows/{id}/execution-limits).
-- Default 2 preserves today's hardcoded behavior on both execution engines
-- (legacy WorkflowExecutionEngine's NODE_MAX_ATTEMPTS=3 and Temporal
-- WorkflowRunWorkflowImpl's setMaximumAttempts(3) both mean "1 initial
-- attempt + 2 retries") so existing workflows see no behavior change until
-- a tenant explicitly edits this value.
ALTER TABLE workflow_definition
    ADD COLUMN max_retry_attempts INTEGER NOT NULL DEFAULT 2;
