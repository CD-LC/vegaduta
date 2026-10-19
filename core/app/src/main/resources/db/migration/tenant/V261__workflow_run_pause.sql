-- Pause/resume for workflow runs ("P2 Operate layer", run control) -
-- operator pause parks a RUNNING run at the next NODE BOUNDARY; resume
-- re-enters the graph at that node with the input it was about to receive.
--
-- The resume envelope mirrors the approval-resume design (see
-- WorkflowApprovalActionExecutor): paused_node_id = the node the traversal
-- was about to hand off to when the pause flag was observed, and
-- paused_node_input = the text output of the previous node that node was
-- about to consume. Both NULL outside a PAUSED state - cleared on resume.
-- The rest of the resume state (the trace) already lives in trace_json,
-- persisted continuously via updateTrace.
--
-- VERSION NUMBER: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream (see V260's header). V260 is this
-- session's eval-gate migration; V261 is its next slot. Repo highest at
-- authoring time (2026-09-23): V240 committed; V241+ and V250-V259 claimed
-- in-flight by sibling sessions - this file is the only V261.
--
-- TENANT SCHEMA, same as workflow_run itself (V20).

ALTER TABLE workflow_run
    ADD COLUMN paused_node_id    VARCHAR(100),
    ADD COLUMN paused_node_input TEXT;

-- "Runs parked waiting for an operator" - the console's paused-runs view
-- and any future sweeper query by status. Partial index: only PAUSED rows
-- are ever looked up this way, and they are rare.
CREATE INDEX idx_workflow_run_paused ON workflow_run (workflow_id)
    WHERE status = 'PAUSED';
