-- SEC-051: public share links (agent_share, workflow_share) get an expiry.
-- New links are stamped with created_at + app.sharing.default-ttl (P30D by
-- default) by the application at mint time. Existing rows are deliberately
-- NOT backfilled: NULL means "legacy, non-expiring" so no live link that a
-- tenant already handed out goes dark on deploy; the public endpoints log
-- each NULL-expiry hit so an operator can see which legacy links remain and
-- revoke/re-share them. Additive + idempotent only.
ALTER TABLE agent_share ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE workflow_share ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

-- SEC-052: the semantic response cache is bound to the caller's principal
-- (sha256 of the chat subjectKey), not just the agent - one user's
-- personalised answer must never be served to another user. Rows written
-- before this column existed stay NULL and are never matched again (the
-- lookup filters on subject_hash = ?); they age out via expires_at (6h).
ALTER TABLE llm_response_cache ADD COLUMN IF NOT EXISTS subject_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_llm_response_cache_agent_subject
    ON llm_response_cache (agent_id, subject_hash);
