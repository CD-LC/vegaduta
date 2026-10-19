-- Consumer secrets vault: named, encrypted secrets a tenant (or one end user
-- within it) stores once and then REFERENCES from agents, tools and MCP
-- servers as {{secret.NAME}} - never by pasting the value into a prompt, an
-- agent config, or a tool argument.
--
-- Why a new table rather than reusing provider_credential (V217): that table
-- is keyed UNIQUE (provider_key) - exactly one row per provider per tenant -
-- because it exists to answer "which credential serves capability TTS". A
-- vault must hold many unrelated, arbitrarily-named secrets, several of which
-- may target the same upstream ("my Stripe key" and "my client's Stripe key"),
-- and must be scopable to a single end user. Those are different shapes, and
-- provider_credential is additionally reserved for core-3's in-flight
-- capability-registry phases 2-5. Deliberately separate; see
-- docs/AGENT-COORDINATION.md 2026-09-11.
--
-- Version V222: flyway.locations merges controlplane/ and tenant/ into ONE
-- shared version sequence (application.yml:308). V221 (controlplane,
-- checkout_session) was the highest across BOTH folders, so V222 is the next
-- actually-free number - checked both, not just tenant/.

CREATE TABLE tenant_secret (
    id                UUID PRIMARY KEY,

    -- The alias used in references: {{secret.STRIPE_KEY}}. Constrained to
    -- [A-Z0-9_] at the service layer so a name can never need escaping inside
    -- the reference syntax, and so it is usable verbatim as an env-var name
    -- when injected into an MCP stdio process.
    name              VARCHAR(64)  NOT NULL,

    -- TENANT: shared by everyone in the workspace (the owner's API keys).
    -- USER:   private to one end user (their own password for a site the
    --         agent acts on). A USER secret is never resolvable for a run
    --         initiated by a different user - enforced in SecretVaultService.
    scope             VARCHAR(16)  NOT NULL,
    owner_user_id     VARCHAR(255),

    kind              VARCHAR(24)  NOT NULL,
    description       VARCHAR(512),

    -- AES-GCM via EncryptionService, same chokepoint as every other secret in
    -- the platform (so APP_ENCRYPTION_KEY rotation covers this table too).
    encrypted_value   TEXT         NOT NULL,

    -- Last 4 plaintext chars ONLY, so the UI can render "sk_live_...4242"
    -- without a decrypt round-trip. Never the whole value, never a prefix of
    -- a short secret (SecretVaultService blanks it below 8 chars).
    value_preview     VARCHAR(16),

    status            VARCHAR(16)  NOT NULL,
    expires_at        TIMESTAMPTZ,

    -- Operational metadata a vault needs and no existing *_credential table
    -- carries: proves "this secret is still in use" before a rotation, and
    -- makes an unexpected access visible next to the audit trail.
    last_used_at      TIMESTAMPTZ,
    rotated_at        TIMESTAMPTZ,

    created_by        VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL
);

-- Name uniqueness is per-owner, and owner_user_id is NULL for TENANT scope.
-- A plain UNIQUE (name, owner_user_id) would NOT work: in SQL every NULL is
-- distinct, so it would happily allow two TENANT-scoped secrets both named
-- STRIPE_KEY. Two partial indexes express the real rule instead.
CREATE UNIQUE INDEX idx_tenant_secret_name_tenant_scope
    ON tenant_secret (name) WHERE scope = 'TENANT';
CREATE UNIQUE INDEX idx_tenant_secret_name_user_scope
    ON tenant_secret (name, owner_user_id) WHERE scope = 'USER';

-- The hot path is SecretReferenceResolver: "resolve NAME for this run's user",
-- which reads ACTIVE rows only.
CREATE INDEX idx_tenant_secret_lookup ON tenant_secret (name, status);
CREATE INDEX idx_tenant_secret_owner  ON tenant_secret (owner_user_id) WHERE owner_user_id IS NOT NULL;

-- A USER-scoped secret without an owner is unresolvable-but-storable, i.e. a
-- silent data-loss bug. Refuse it at the database, not just in Java.
ALTER TABLE tenant_secret ADD CONSTRAINT chk_tenant_secret_owner_matches_scope
    CHECK ((scope = 'USER' AND owner_user_id IS NOT NULL)
        OR (scope = 'TENANT' AND owner_user_id IS NULL));
