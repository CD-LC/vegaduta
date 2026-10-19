-- B2B/B2C/Family tiering + Simple/Advanced experience mode (default for
-- every agent the tenant creates, see Agent.experience_mode in the tenant
-- schema migration V35). Both asked at signup from here on.
--
-- Existing rows (pre-dating this column) default to B2C/SIMPLE rather than
-- failing the migration - the least-surprising guess for a tenant nobody
-- ever asked, and neither value grants MORE access than before (B2C is the
-- most restrictive seat count; SIMPLE only changes which UI editors show,
-- never what a request is authorized to do). An operator can hand-correct
-- a specific tenant's account_type via psql if the guess is wrong for a
-- pre-existing customer.
ALTER TABLE control_plane.tenant
    ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'B2C',
    ADD COLUMN experience_mode VARCHAR(16) NOT NULL DEFAULT 'SIMPLE';

ALTER TABLE control_plane.tenant ALTER COLUMN account_type DROP DEFAULT;
ALTER TABLE control_plane.tenant ALTER COLUMN experience_mode DROP DEFAULT;
