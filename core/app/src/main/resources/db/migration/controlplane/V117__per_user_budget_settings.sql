-- PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 (Budgets & Limits) Phase P1 -
-- the platform's per-USER daily DOLLAR budget on platform-hosted LLM usage.
--
-- These are plain rows in the existing generic control_plane.platform_setting
-- table (V91), NOT a new table: per PlatformSettingsService's javadoc, a new
-- admin-tunable knob is "add one migration row, add one typed getter", no new
-- schema. PerUserBudgetService reads them via PlatformSettingsService and
-- falls back to these exact same defaults in-code if a row is absent, so the
-- service works even against a database that predates this migration; seeding
-- them here is purely so they show up (and are editable) in the admin
-- /api/platform/settings list and /api/platform/per-user-budget endpoint from
-- first boot.
--
-- Version V117: the tenant + control_plane migration folders share ONE Flyway
-- sequence scanned recursively; V117 is the global next-free number (do not
-- renumber below the current global max). control_plane-scoped because the
-- platform_setting table lives in the control_plane schema.
--
-- Units: daily-usd is a plain USD decimal string; window-seconds an int;
-- split-pct.<modality> each modality's cap as a PERCENT of daily-usd (these
-- are independent sub-caps, not a shared pool - text/image/voice each 100%,
-- video 0% because video is excluded from the included budget / pay-per-use);
-- fallback price is $ per 1,000 tokens, used only when the pricing catalog
-- can't price the hosted model that served a call.
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('platform.budget.per-user.enabled', 'true', 'Master switch for the per-user daily dollar budget on platform-hosted LLM usage. When false, budget metering is skipped entirely.'),
    ('platform.budget.per-user.daily-usd', '0.15', 'Base daily budget per user, in USD, for platform-hosted LLM usage (governance doc section 4 default).'),
    ('platform.budget.per-user.window-seconds', '86400', 'Length in seconds of the rolling per-user budget window (86400 = 24h).'),
    ('platform.budget.per-user.split-pct.text', '100', 'TEXT modality cap as a percentage of daily-usd. Independent sub-cap.'),
    ('platform.budget.per-user.split-pct.image', '100', 'IMAGE modality cap as a percentage of daily-usd. Independent sub-cap.'),
    ('platform.budget.per-user.split-pct.voice', '100', 'VOICE modality cap as a percentage of daily-usd. Independent sub-cap.'),
    ('platform.budget.per-user.split-pct.video', '0', 'VIDEO modality cap as a percentage of daily-usd. Default 0 = excluded from the included budget (pay-per-use).'),
    ('platform.budget.per-user.fallback-usd-per-1k-tokens', '0.00015', 'Estimated USD per 1,000 tokens used to price a hosted-pool call when the pricing catalog does not recognise the model.')
ON CONFLICT (key) DO NOTHING;
