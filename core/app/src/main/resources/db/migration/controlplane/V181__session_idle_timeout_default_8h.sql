-- Raise the client-side idle-logout window from 10 minutes to 8 hours.
--
-- V172 seeded session.idle-timeout-seconds = 600. Its own comments say 600 was
-- chosen only to preserve the previously hardcoded 10-minute cutoff, not
-- because 10 minutes was the intended policy. In practice it signed people out
-- while they were reading a long page or briefly away from the desk - the
-- complaint that prompted this change ("user should be logged in till he
-- clicks on logout or timeout message asking to extend"). The paired
-- "still there?" warning modal, which was already built, covers the edge.
--
-- CONTROL-PLANE migration (platform_setting lives in control_plane), and the
-- Flyway version namespace is shared with the tenant/ folder - V180 was the
-- highest taken across BOTH when this was written, so this is V181.
--
-- Deliberately conditional on the value still being the seeded 600: if an
-- operator has already tuned this key, that is a decision, and a migration has
-- no business overwriting it. Re-running is therefore a no-op, which also
-- means an environment that has already been tuned by hand stays tuned.
UPDATE control_plane.platform_setting
   SET value = '28800'
 WHERE key = 'session.idle-timeout-seconds'
   AND value = '600';

-- Insert for any environment provisioned before the key existed. ON CONFLICT
-- DO NOTHING so this never fights the UPDATE above.
INSERT INTO control_plane.platform_setting (key, value)
     VALUES ('session.idle-timeout-seconds', '28800')
ON CONFLICT (key) DO NOTHING;

INSERT INTO control_plane.platform_setting (key, value)
     VALUES ('session.idle-warning-seconds', '60')
ON CONFLICT (key) DO NOTHING;
