-- Device capability declaration: a paired device reports which of the
-- protocol's commands it can actually honour (a browser PWA has camera +
-- geolocation but no SMS; an Android client has the reverse). Stored as a
-- comma-separated key list - the same flat VARCHAR shape this table uses
-- for platform. NULL means "never declared", treated as unknown rather
-- than denied.
ALTER TABLE mobile_device ADD COLUMN capabilities VARCHAR(512);
