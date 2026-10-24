-- V670: eval runs default to the full production pipeline; the tool-less replay becomes the explicit "quick" mode.
--
-- An eval used to replay text-only unless its dataset opted in, so a green eval said little about how the agent
-- behaves with its tools. A run now replays through the production pipeline (tool selection, guards, approval gate,
-- step ceilings, budget) in the eval sandbox by default; a quick text-only replay is chosen explicitly, per run or
-- as a dataset's or workspace's standing choice (platform property app.quality.eval.default-mode, workspace setting
-- quality.eval.defaultMode).
--
--   * eval_run.eval_mode: the mode the run replayed in, FULL or QUICK, frozen when the run is triggered so a finished
--     run always says what it measured. Runs recorded before this migration are backfilled from the dataset's mode
--     at the time of the migration (the run row never stored it): FULL where the dataset ran with tools, QUICK
--     otherwise. That is the best the data allows, not a guarantee for a dataset edited since the run.
--
--   * eval_dataset.tool_execution_enabled: now a nullable PIN. TRUE = the dataset always runs the full pipeline,
--     FALSE = it always runs quick, NULL = it pins nothing and follows the default. The column was NOT NULL DEFAULT
--     FALSE, so FALSE could not tell "never chose" from "chose text-only"; every dataset that held it becomes NULL
--     and so follows the new default. TRUE rows are unchanged. The dataset's content hash treats NULL and FALSE
--     alike ('0'), so no existing run or gate binding goes stale.

ALTER TABLE eval_run ADD COLUMN eval_mode VARCHAR(8) NOT NULL DEFAULT 'QUICK'
    CONSTRAINT ck_eval_run_mode CHECK (eval_mode IN ('FULL', 'QUICK'));

-- Before the datasets are touched: the old flag is what says how a past run replayed.
UPDATE eval_run r
   SET eval_mode = 'FULL'
  FROM eval_dataset d
 WHERE d.id = r.dataset_id
   AND d.tool_execution_enabled = TRUE;

ALTER TABLE eval_dataset ALTER COLUMN tool_execution_enabled DROP NOT NULL;
ALTER TABLE eval_dataset ALTER COLUMN tool_execution_enabled DROP DEFAULT;

UPDATE eval_dataset SET tool_execution_enabled = NULL WHERE tool_execution_enabled = FALSE;
