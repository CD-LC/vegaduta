-- Ambient agents: a schedule can now run in SENTINEL mode. The cadence'd
-- firing is a watch brief ("monitor this stream, escalate only when...")
-- rather than a task - the agent ends its run with a ```sentinel verdict
-- block instead of a deliverable, and AgentSentinelSweep turns that verdict
-- into a Notification or a parked approval_request proposal.
--
-- agent_schedule.sentinel_enabled: config flag, set via the schedule upsert.
-- Rejected for WORKFLOW targets (a workflow run has no free-text result for a
-- verdict to live in).
--
-- agent_schedule_run.sentinel_outcome: what the sweep decided about this
-- firing - NULL means "not yet evaluated", which makes the sweep's
-- not-yet-judged read a cheap IS NULL filter and makes exactly-once
-- evaluation survive replica races and JVM restarts. Values are plain
-- strings (QUIET | NOTIFIED | PROPOSED | NO_VERDICT | JOB_FAILED |
-- JOB_GONE), matching this table's existing status/trigger_kind
-- VARCHAR-not-ENUM convention.
ALTER TABLE agent_schedule
    ADD COLUMN sentinel_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent_schedule_run
    ADD COLUMN sentinel_outcome VARCHAR(24);

-- What the sweep decided in detail - the approval request id for PROPOSED,
-- the job's terminal state for JOB_FAILED, the verdict's title otherwise.
-- Kept narrow: this is audit context for the runs list, not the notification
-- body (which lives on the notification row it produced).
ALTER TABLE agent_schedule_run
    ADD COLUMN sentinel_outcome_note VARCHAR(500);
