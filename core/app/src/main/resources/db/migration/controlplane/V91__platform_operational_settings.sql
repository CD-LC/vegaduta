-- Direct feedback: "i want a central control in admin platform maintenance
-- to set all these values in UI" - triggered by hitting SignupRateLimiter's
-- hardcoded app.signup.rate-limit.max-attempts/window-minutes (@Value,
-- fixed at JVM startup, only changeable by editing application.yml/an env
-- var and restarting core) while testing signup repeatedly.
--
-- Generic key-value table rather than a dedicated column-per-setting table
-- (the pattern control_plane.platform_alert_settings/platform_kill_switch
-- use) - deliberately, since the ask here is explicitly for a general
-- "set all these values" control surface, not one more single-purpose
-- singleton row. New operational knobs can be added later with just an
-- INSERT (or PlatformSettingsService.getInt's own defaultIfMissing
-- fallback), no new migration/entity/endpoint required each time - see
-- PlatformSettingsService's javadoc.
--
-- Renumbered V87 -> V91 (same class of fix as the V12/V13 and V60/V61
-- collisions documented in application.yml's flyway.locations comment):
-- this file's original V87 number collided with
-- tenant/V87__knowledge_chunks_fts.sql, which had already landed first and
-- already had tenant/V88-V90 built on top of it, so this file moved instead
-- of the tenant one. Bumped past tenant/V90__prompt_version.sql (the latest
-- migration in either folder as of this rename) to stay clear of the same
-- "combined sequence" numbering both folders share.
CREATE TABLE control_plane.platform_setting (
    key         VARCHAR(120) PRIMARY KEY,
    value       VARCHAR(500) NOT NULL,
    description VARCHAR(500),
    updated_by  VARCHAR(255),
    updated_at  TIMESTAMPTZ
);

-- Seeded with the exact values SignupRateLimiter's @Value defaults already
-- used (app.signup.rate-limit.max-attempts:5 / window-minutes:60) - this
-- migration changes WHERE the value lives (DB row, admin-editable) not
-- WHAT the value is, per the "minimum required change" standard already
-- applied to this codebase's other config fixes.
INSERT INTO control_plane.platform_setting (key, value, description) VALUES
    ('signup.rate-limit.max-attempts', '5', 'Max POST /api/signup attempts allowed per client IP within the rate-limit window, before returning 429.'),
    ('signup.rate-limit.window-minutes', '60', 'Length, in minutes, of the rolling window signup.rate-limit.max-attempts is counted over.');
