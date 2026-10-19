-- Autonomy/Guardrails Strategy Stage C: retry-with-backoff for
-- ApprovalActionExecutor failures. Before this, any executor failure
-- (including a plainly transient one, e.g. a network blip calling Twilio)
-- failed permanently on the very first attempt with no retry at all.
--
-- retry_count defaults 0 to backfill existing rows (every approval_request
-- created before this migration has made zero retry attempts, which is
-- true) - default dropped immediately after, matching this migration
-- series' established "backfill existing data, then require explicit
-- values going forward" convention (see V12/V13's header comments for the
-- same reasoning applied to their own new columns).
ALTER TABLE approval_request
    ADD COLUMN retry_count   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at TIMESTAMPTZ;

ALTER TABLE approval_request ALTER COLUMN retry_count DROP DEFAULT;
