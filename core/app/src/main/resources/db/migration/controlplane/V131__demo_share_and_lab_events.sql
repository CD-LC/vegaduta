-- Shareable sandbox agents + lab conversion events (core/tenancy/demo,
-- core/agents/demo) - the columns behind POST /api/demo/session/share, the
-- public /api/demo/share/{shareId} surface, and the lab_started/lab_completed
-- funnel events.
--
-- Version note: this repo's Flyway locations merge controlplane/ and tenant/
-- into one shared version sequence (see application.yml's flyway comment).
-- Controlplane is at V130 and tenant tops out at V121, so this is V131 -
-- verified against BOTH folders, per the version-collision that happened
-- before V115.
--
-- 1. share_id - the capability credential for a share link. Unlike token_hash
--    it is stored in the CLEAR, deliberately: the share URL is meant to be
--    passed around (that is the feature), so the value is not a secret the
--    database must be unable to reveal - it is an unguessable public handle
--    (144 bits of SecureRandom, URL-safe Base64). NULL until the owner
--    explicitly shares; a session that never shares never has one.
--    Uniqueness is a partial index because most rows stay NULL.
--
-- 2. share_created_at / share_revoked / share_reported_count - lifecycle and
--    abuse controls. share_revoked flips TRUE automatically when
--    share_reported_count reaches the report threshold (default 3, admin-
--    tunable) and is deliberately never flipped back by code - un-revoking a
--    reported share is an operator decision made in SQL, not an endpoint.
--
-- 3. share_view_count / share_viewer_count - denormalised counters for the
--    admin funnel (GET /api/platform/demo-metrics), same posture V127 took
--    with image_count: one aggregate over a table already in cache instead of
--    a new rollup table. view_count increments on every public GET of the
--    share meta; viewer_count increments only when a NEW distinct viewer IP
--    is admitted to the Redis viewer set, so it survives Redis restarts as an
--    approximate-but-durable floor.
--
-- 4. lab_started_at / lab_completed_at - the Academy-lab conversion events
--    (POST /api/demo/session/event). Timestamps rather than booleans so the
--    funnel can later measure time-to-complete; idempotent per session by
--    definition (set once, never overwritten).

ALTER TABLE control_plane.demo_session
    ADD COLUMN share_id VARCHAR(64),
    ADD COLUMN share_created_at TIMESTAMPTZ,
    ADD COLUMN share_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN share_reported_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN share_view_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN share_viewer_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN lab_started_at TIMESTAMPTZ,
    ADD COLUMN lab_completed_at TIMESTAMPTZ;

-- Partial unique index: enforces uniqueness AND serves the hot public lookup
-- (resolve-by-share-id on every share page hit), while costing nothing for
-- the majority of sessions that never share.
CREATE UNIQUE INDEX idx_demo_session_share_id
    ON control_plane.demo_session (share_id)
    WHERE share_id IS NOT NULL;
