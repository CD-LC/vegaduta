-- Tenant REST API keys (machine credentials for the /api/** surface).
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into
-- ONE version stream (see V175's note). Highest before this change: V482
-- (tenant, poll triggers + webhook signatures), so V483 is the next free slot.
--
-- A tenant admin mints a key for headless callers (CI, partner systems,
-- scripts) that cannot run an OIDC login: Authorization: Bearer vdak_...
-- authenticates through the normal security chain with PERM_* authorities
-- drawn from the key's stored permission set - a subset of the issuer's own
-- effective permissions at create time (no scope the issuer didn't hold).
--
-- Design notes mirrored from mcp_server_access_grant (V104+): the plaintext
-- is shown once at mint/rotate and never stored - only a SHA-256 hash (which
-- the control_plane channel_account_link row also keys on) and an 8-char
-- display prefix. permissions_json is a JSON array of catalog permission
-- names (admin.permission.Permission values, WITHOUT the PERM_ authority
-- prefix). created_by records the issuing subject for audit; the key's own
-- subject on calls is the synthetic "apikey:<name>:<shortId>", never the
-- creator's, so usage is attributable to the credential, not the human.
CREATE TABLE tenant_api_key (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(128) NOT NULL,
    api_key_hash          VARCHAR(64) NOT NULL UNIQUE,
    api_key_display_prefix VARCHAR(16) NOT NULL,
    permissions_json      TEXT NOT NULL DEFAULT '[]',
    created_by            VARCHAR(128),
    expires_at            TIMESTAMPTZ,
    last_used_at          TIMESTAMPTZ,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);

-- Admin console list; per-tenant tables are small but this is the common read.
CREATE INDEX tenant_api_key_created_at ON tenant_api_key (created_at);
