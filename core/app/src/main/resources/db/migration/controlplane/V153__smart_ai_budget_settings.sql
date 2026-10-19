-- Smart AI layer: a single platform-wide master switch plus a dollar
-- budget governor for every AI-native platform feature (in-console
-- copilot, AI-generated nudges/digests, error explanations, etc.) - see
-- PlatformFeatureBudgetService, sibling of PerUserBudgetService/V117.
--
-- Plain rows in the existing generic control_plane.platform_setting table
-- (V91), not a new table - same "add one migration row, add one typed
-- getter" posture PlatformSettingsService's javadoc describes.
-- PlatformFeatureBudgetService falls back to these exact same defaults
-- in-code if a row is absent, so it works even against a database that
-- predates this migration; seeding here is purely so the values show up
-- (and are editable) in the admin /api/platform/settings list and the
-- typed /api/platform/smart-ai endpoint from first boot.
--
-- Version V151: the tenant + control_plane migration folders share ONE
-- Flyway sequence scanned recursively (see V117's own comment) - V151 is
-- the current global next-free number as of 2026-07-29 (tenant's
-- V150__agent_text_model.sql is the highest existing version); do not
-- renumber below the current global max.
--
-- Per-feature switches/caps (platform.smart-ai.feature.<key>.enabled /
-- .daily-usd) are intentionally NOT seeded here - the set of Smart AI
-- features is expected to grow over time, and PlatformFeatureBudgetService
-- falls back to enabled=true / default-feature-daily-usd for any feature
-- key with no row of its own, so a brand-new feature never needs a
-- migration just to turn on.
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('platform.smart-ai.enabled', 'true', 'Master switch for the entire Smart AI feature layer (in-console copilot, AI nudges/digests, error explanations, etc). When false, every Smart AI feature degrades to its non-AI behaviour immediately, no redeploy.'),
    ('platform.smart-ai.budget.window-seconds', '86400', 'Length in seconds of the rolling Smart AI budget window (86400 = 24h), shared by the master cap and every per-feature cap.'),
    ('platform.smart-ai.budget.master-daily-usd', '2.00', 'Combined daily USD ceiling across every Smart AI feature. Whichever cap (this one or a feature''s own) is hit first blocks that feature''s next AI call.'),
    ('platform.smart-ai.budget.default-feature-daily-usd', '0.25', 'Daily USD cap used for any Smart AI feature that has no feature-specific override row (platform.smart-ai.feature.<key>.daily-usd).')
ON CONFLICT (key) DO NOTHING;
