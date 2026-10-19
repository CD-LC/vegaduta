-- Direct feedback (2026-08-02): "system throwing out logged in user, without
-- asking to extend the session... should never throw... force logout if he
-- is not idle, there should be setting in admin platform maintenance to set
-- the default consumer being idle on platform without any action."
--
-- Rows for the existing generic control_plane.platform_setting table (see
-- V91__platform_operational_settings.sql) - no new table needed, this is
-- exactly the "add one migration row" case PlatformSettingsService's javadoc
-- describes. Shows up automatically in the existing Ecosystem Maintenance /
-- Platform Settings admin screen (web-admin's abuse/page.tsx), editable via
-- the existing PUT /api/platform/settings/{key}.
--
-- '600'/'60' preserve IdleLogout.tsx's previous hardcoded 10-minute cutoff
-- and its (new) 60-second warning window exactly - this migration changes
-- WHERE the value lives (DB row, admin-editable, with a warning prompt
-- before logout) not WHAT the timeout defaults to.
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('session.idle-timeout-seconds', '600', 'Seconds of no user activity (mouse/keyboard/scroll/touch) before a signed-in consumer is automatically logged out.'),
    ('session.idle-warning-seconds', '60', 'Seconds before the idle timeout that a "you will be signed out" warning with a Stay signed in button is shown.');
