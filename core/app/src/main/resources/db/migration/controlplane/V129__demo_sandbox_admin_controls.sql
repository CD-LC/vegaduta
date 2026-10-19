-- Platform-admin CONTROLS + ANALYTICS for the anonymous demo sandbox
-- (GET/PUT /api/platform/demo-settings, GET /api/platform/demo-metrics -
-- DemoSandboxController / DemoSandboxSettingsService / DemoSandboxMetricsService
-- in core/platform).
--
-- Version note: this repo's Flyway locations merge controlplane/ and tenant/
-- into one shared version sequence (see application.yml's flyway comment).
-- V126 (demo_session + tenant.is_demo), V127 (demo_session.image_count +
-- demo_generated_image) and V128 (demo_channel_link/demo_channel_message) are
-- taken by the sandbox workstreams this one reads from, so this is V129.
--
-- NO NEW TABLE. Everything the admin console needs is already recorded by
-- V126-V128; this migration only (1) makes the analytics queries cheap and
-- (2) makes the existing runtime knobs visible and editable, which is exactly
-- the "one migration row, one typed getter" posture PlatformSettingsService's
-- javadoc asks new knobs to follow.

-- ---------------------------------------------------------------------------
-- 1. Indexes for the analytics queries
-- ---------------------------------------------------------------------------
-- V126 indexed expires_at (the expiry job's scans) and tenant_id (the reverse
-- conversion lookup). Neither helps the admin console, whose every query is
-- either bucketed by created_at (today / 7d / 30d windows, the 14-30 day
-- created-vs-converted time series) or ordered by it (the recent-sandboxes
-- operator table). One index covers all of them.
CREATE INDEX IF NOT EXISTS idx_demo_session_created_at
    ON control_plane.demo_session (created_at DESC);

-- The conversion series buckets by converted_at, and converted rows are - by
-- the nature of a funnel - a small minority of the table, so a partial index
-- stays tiny while still turning "which sandboxes converted on day X" into an
-- index scan instead of a seq scan over every sandbox ever created.
CREATE INDEX IF NOT EXISTS idx_demo_session_converted_at
    ON control_plane.demo_session (converted_at)
    WHERE converted_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. Seed the demo knobs into control_plane.platform_setting
-- ---------------------------------------------------------------------------
-- Every key below is ALREADY read at runtime by the demo services (see the
-- getInt/getBoolean calls in DemoSessionService, DemoSessionRateLimiter,
-- DemoSessionExpiryJob and DemoImageService) - they simply had no seeded row,
-- so they silently fell back to their @Value defaults and did not appear in
-- the admin Settings list at all. Seeding them changes WHERE the value lives
-- (an admin-editable DB row) not HOW it is read - the same "minimum required
-- change" standard V91 and V120 followed.
--
-- ON CONFLICT DO NOTHING, deliberately: if an operator has already tuned one
-- of these by hand (the generic PUT /api/platform/settings/{key} surface has
-- existed since V91), this migration must not silently reset their value.
--
-- THREE VALUES BELOW ARE NOT THE CODE FALLBACK, on purpose - direct owner
-- instruction, "one demo per IP, and if they don't succeed they wait about an
-- hour before creating a fresh one":
--   demo.max-live-sessions-per-ip  seeded 1  (code fallback 2)
--   demo.rate-limit.max-attempts   seeded 1  (code fallback 3)
--   demo.rate-limit.window-minutes seeded 60 (code fallback 1440)
-- Together those two rate-limit rows ARE the cooldown: one creation per IP
-- per 60-minute window. Expressed through the knobs the running code already
-- reads rather than as a new "cooldown" key, so this takes effect with no
-- change to core/tenancy/demo and no redeploy. GET /api/platform/demo-settings
-- surfaces both the effective value and the code fallback so the difference is
-- never hidden from the operator.
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('demo.enabled', 'true',
     'Demo sandbox: master kill switch for the whole no-signup "try it" surface. False makes /api/demo/** refuse new sandboxes (existing ones keep working until they expire).'),
    ('demo.max-live-sessions-per-ip', '1',
     'Demo sandbox: how many LIVE (unexpired) sandboxes one IP may hold at once. Owner intent is 1 - "one demo per IP". Code fallback if this row is removed is 2.'),
    ('demo.rate-limit.max-attempts', '1',
     'Demo sandbox: how many sandboxes one IP may CREATE per cooldown window (see demo.rate-limit.window-minutes). With window=60 this is the "wait about an hour before a fresh one" cooldown. Code fallback is 3.'),
    ('demo.rate-limit.window-minutes', '60',
     'Demo sandbox: the per-IP creation cooldown window, in minutes. Code fallback is 1440 (24h); seeded at 60 per owner intent.'),
    ('demo.lifetime-days', '5',
     'Demo sandbox: TTL in days. A sandbox is retired (tenant suspended) by DemoSessionExpiryJob this many days after creation.'),
    ('demo.message-limit', '20',
     'Demo sandbox: how many chat turns one sandbox may spend before it stops answering. The per-sandbox inference cap.'),
    ('demo.reminder-hours-before-expiry', '36',
     'Demo sandbox: how many hours before expiry the one-time "your sandbox is ending" reminder email goes out (only to visitors who volunteered an address).'),
    ('demo.image.enabled', 'true',
     'Demo sandbox: whether a sandbox may generate images at all. Read by DemoImageService.'),
    ('demo.image.per-session-limit', '2',
     'Demo sandbox: how many images one sandbox may generate in total. Read by DemoImageService.'),
    ('demo.telegram.enabled', 'true',
     'Demo sandbox: kill switch for demo Telegram channel linking. DECLARED HERE, NOT YET ENFORCED - DemoChannelLinkService does not read this key yet; whichever workstream owns core/tenancy/demo channel linking should gate new TELEGRAM links on platformSettings.getBoolean("demo.telegram.enabled", true) for this control to bite.')
ON CONFLICT (key) DO NOTHING;
