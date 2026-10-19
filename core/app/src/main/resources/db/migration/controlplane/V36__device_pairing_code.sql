-- Mobile SDK (#157) - the short-lived, single-use code that gets a phone
-- from "just opened the app" to a real tenant-scoped device, before it has
-- any credential of its own (see DevicePairingCode's javadoc). Lives in
-- control_plane for the same reason channel_account_link (V6) does: which
-- tenant a code belongs to is a cross-tenant question, asked by a request
-- (the device's claim call) that has no JWT and therefore no schema to run
-- a tenant-scoped query against yet.
--
-- No uniqueness constraint on `code` itself, deliberately - see the entity
-- javadoc: a 6-digit code is a small, constantly-recycling space, and only
-- collisions among still-usable (unexpired, unconsumed) codes matter, which
-- DevicePairingService checks in application code instead.
CREATE TABLE control_plane.device_pairing_code (
    id            UUID PRIMARY KEY,
    code          VARCHAR(6) NOT NULL,
    tenant_schema VARCHAR(63) NOT NULL,
    created_by    VARCHAR(190),
    created_at    TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed      BOOLEAN NOT NULL DEFAULT FALSE
);

-- The lookup DevicePairingCodeRepository#findByCodeAndConsumedFalse runs on
-- every claim attempt - a partial index on the still-unconsumed rows only,
-- the small slice this query actually needs, rather than indexing the whole
-- (constantly growing, mostly-consumed) table.
CREATE INDEX idx_device_pairing_code_unconsumed
    ON control_plane.device_pairing_code (code)
    WHERE NOT consumed;
