-- Activity-based demo sandbox expiry (2026-07-26).
--
-- Previously a sandbox's expiry was fixed at creation (created_at +
-- demo.lifetime-days, default 5 days) regardless of whether the visitor ever
-- came back. Direct product request: a never-touched sandbox should retire
-- quickly (the short demo.inactivity-timeout-hours grace window, default
-- 24h), while an actively-used one keeps sliding its deadline forward by the
-- longer, admin-configurable demo.lifetime-days on every real turn.
--
-- Backfilled to created_at for existing rows - the honest "we don't actually
-- know when they last did anything, so treat existing sandboxes as having
-- last been active at creation" default. This may retire a handful of
-- existing live-but-idle sandboxes slightly earlier than their original
-- fixed expiry would have; that's the correct new behavior applying
-- retroactively, not a bug.
ALTER TABLE control_plane.demo_session
    ADD COLUMN last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

UPDATE control_plane.demo_session SET last_activity_at = created_at;

ALTER TABLE control_plane.demo_session ALTER COLUMN last_activity_at DROP DEFAULT;
