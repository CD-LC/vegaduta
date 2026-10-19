-- Optional operator note sent with a workflow run (the Run modal's "Run comment").
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE version stream. Highest
-- on main when this was written: V485; V490 leaves room for sibling work that landed in parallel.
--
-- Stored beside the run instead of being appended to the run input, so nodes and branches (a condition
-- on "refund", say) never see it. NULL for every run that predates the column or was started without one.
ALTER TABLE workflow_run ADD COLUMN IF NOT EXISTS run_comment VARCHAR(2000);
