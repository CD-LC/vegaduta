-- Platform ops console (2026-10-09): maintenance mode and the announcement banner. Each is ONE JSON document in
-- platform_setting so a PUT is a single atomic row write (PlatformBroadcastService). Seeded DISABLED - this migration
-- changes no behaviour on its own. Written only through PUT /api/platform/maintenance (X-Master-Key) and
-- PUT /api/platform/announcement; the generic PUT /api/platform/settings/{key} refuses both keys.
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('platform.ops.maintenance', '{"enabled":false,"message":"","endsAt":null,"startedAt":null}',
     'Maintenance mode (JSON). While enabled, /api/** answers 503 except platform-admin, /api/platform/**, /api/public/status and payment webhooks. Edit via the ops console only.'),
    ('platform.ops.announcement', '{"enabled":false,"message":"","level":"info","linkUrl":null,"expiresAt":null}',
     'Announcement banner (JSON) served by GET /api/public/status. Edit via the ops console only.')
ON CONFLICT (key) DO NOTHING;
