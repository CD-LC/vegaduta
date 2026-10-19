-- Per-agent override of the tenant-wide Simple/Advanced default (see
-- control_plane.tenant.experience_mode, migration V13 in controlplane).
-- NULL means "inherit whatever the tenant's current default is" - the
-- common case, and the reason this isn't NOT NULL with its own default:
-- an agent created before this column existed, or one nobody ever touched
-- this setting on, should keep tracking the tenant default if that default
-- ever changes, not freeze at whatever the column's own default happened
-- to be at creation time.
ALTER TABLE agent ADD COLUMN experience_mode VARCHAR(16);
