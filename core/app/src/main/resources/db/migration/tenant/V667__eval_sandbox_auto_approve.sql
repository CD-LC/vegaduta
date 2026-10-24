-- Evals can run through the same turn pipeline real users hit (tools, guardrails, step limits). A tool call
-- that would wait for a person normally parks the turn; for an offline eval in a NON-PRODUCTION workspace the
-- dataset may instead ask the eval sandbox to approve it (never for shell/coding/scheduling/agent-to-agent
-- calls or anything that moves money or deletes - those still park).
--
--  * eval_dataset.auto_approve_tools: the dataset's choice, meaningful only together with
--    tool_execution_enabled (V213). FALSE for every existing dataset: no existing row changes behaviour, and
--    tool_execution_enabled keeps its V213 default of FALSE (new datasets take the platform default
--    app.quality.eval.default-tool-execution in the service, not here).
--  * eval_run.auto_approved: snapshot, taken when the run is triggered, of whether the run replayed under that
--    auto-approval, so a finished run always says how it was produced even if the dataset is edited later.

ALTER TABLE eval_dataset ADD COLUMN auto_approve_tools BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE eval_run ADD COLUMN auto_approved BOOLEAN NOT NULL DEFAULT FALSE;
