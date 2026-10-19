-- Newest-first capped/offset list endpoints (GET /api/approvals, GET /api/channels/contacts)
-- ORDER BY created_at DESC, id DESC LIMIT n OFFSET m (id is the deterministic tiebreaker so
-- offset pages never duplicate/skip equal-timestamp rows): without an index Postgres sorts
-- every matching row per call. IF NOT EXISTS keeps re-runs and per-tenant replays safe.
CREATE INDEX IF NOT EXISTS idx_approval_request_created_at ON approval_request (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_channel_contact_created_at ON channel_contact (created_at DESC, id DESC);
