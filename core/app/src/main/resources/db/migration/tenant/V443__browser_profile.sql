-- Browser profiles: named, domain-scoped, encrypted Playwright storageState
-- (cookies + localStorage) that a browser agent loads into a fresh headless
-- context and refreshes when the session ends (app.browser.profiles.enabled,
-- default OFF - with the flag off nothing reads or writes these tables).
--
-- Tenant-schema tables (unqualified, resolved via search_path), so there is no
-- tenant_id column. state_encrypted holds EncryptionService (AES-256-GCM)
-- ciphertext of the domain-filtered storageState JSON; it is NEVER returned by
-- any API (the REST layer only exposes state_bytes / state_entries /
-- timestamps). allowed_domains is a comma-separated list of lower-case
-- hostnames the profile may be used on. scope TENANT = shared by the tenant
-- (owner_subject NULL); scope USER = visible to and manageable by owner_subject
-- only. expires_at slides forward by ttl_days on every save (NULL = no expiry).
CREATE TABLE IF NOT EXISTS browser_profile (
    id              UUID PRIMARY KEY,
    name            VARCHAR(120)  NOT NULL,
    scope           VARCHAR(16)   NOT NULL DEFAULT 'TENANT',
    owner_subject   VARCHAR(255),
    allowed_domains TEXT          NOT NULL,
    state_encrypted TEXT,
    state_bytes     INTEGER       NOT NULL DEFAULT 0,
    state_entries   INTEGER       NOT NULL DEFAULT 0,
    ttl_days        INTEGER       NOT NULL DEFAULT 30,
    expires_at      TIMESTAMPTZ,
    last_saved_at   TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_browser_profile_owner_name
    ON browser_profile ((COALESCE(owner_subject, '')), lower(name));

-- One profile per agent (an agent has a single "logged-in identity"); one
-- profile may serve several agents. Deleting a profile detaches its agents.
CREATE TABLE IF NOT EXISTS agent_browser_profile (
    agent_id    UUID PRIMARY KEY,
    profile_id  UUID         NOT NULL REFERENCES browser_profile (id) ON DELETE CASCADE,
    attached_by VARCHAR(255),
    attached_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_agent_browser_profile_profile
    ON agent_browser_profile (profile_id);
