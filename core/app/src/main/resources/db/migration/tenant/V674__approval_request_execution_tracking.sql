-- Approved but never executed: HumanApprovalGateService commits APPROVED and only then runs the
-- action, so a process that died in between left a row that looked decided and was never run, with
-- nothing to find it. These two columns say how far an approved action got:
--
--   execution_started_at    set (under a row lock) by the one caller that runs the action
--   execution_completed_at  set when the action ended, whatever the outcome
--
-- APPROVED with neither set = never started (safe to run once); started but not completed = outcome
-- unknown (reported, never re-run, so a side effect cannot happen twice). ApprovalRecoveryJob reads them.
--
-- Every row that exists now is, as far as anyone can tell, finished: it is backfilled as completed so
-- the first sweep after the upgrade does not run a year of old approvals again.
ALTER TABLE approval_request
    ADD COLUMN execution_started_at   TIMESTAMPTZ,
    ADD COLUMN execution_completed_at TIMESTAMPTZ;

UPDATE approval_request
   SET execution_completed_at = COALESCE(decided_at, created_at)
 WHERE status <> 'PENDING';

CREATE INDEX idx_approval_request_unfinished
    ON approval_request (decided_at)
    WHERE status = 'APPROVED' AND execution_completed_at IS NULL;
