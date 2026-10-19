-- Agent identity & delegated trust (delegated-authorization half).
--
-- agent_delegation_grant: a recorded authorization by which one user lets an
-- agent act on their behalf toward a specific audience ("agent:<id> acting-for
-- user:<sub>, scope:..., aud:..."). RFC 8693 token-exchange semantics: minted
-- tokens carry sub=agent, act=delegator, aud=audience, scope=grant scopes.
-- Grants outlive individual tokens (a grant is the revocable unit; tokens are
-- short-lived projections of it), which is why revocation lands here and not
-- per-token.
--
-- agent_delegation_token: one row per minted token (keyed by jti). Tokens are
-- self-contained Ed25519 JWTs and CAN be verified statelessly, but this table
-- is what makes introspection answer "still valid?" honestly - a revoked grant
-- or individually revoked jti shows up here. expiry is a hard bound regardless
-- of grant status.
--
-- wallet_id on the token: set when the grant's scopes include pay:* - it pins
-- WHICH wallet the token may spend from, so a delegation for payments cannot
-- be aimed at a different wallet of the same agent after minting.
--
-- Tenant-resident: grants are a tenant's own records; the public agent
-- directory (control_plane, V402) only carries signed snapshots.

CREATE TABLE agent_delegation_grant (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    agent_id               UUID         NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    granted_by             VARCHAR(128) NOT NULL,      -- delegator's JWT subject
    audience               VARCHAR(512) NOT NULL,      -- aud every token is bound to
    scopes                 TEXT         NOT NULL,      -- JSON array, e.g. ["read:calendar","pay:x402"]
    max_token_ttl_seconds  BIGINT       NOT NULL CHECK (max_token_ttl_seconds > 0),
    expires_at             TIMESTAMPTZ  NOT NULL,      -- grant-level hard expiry
    wallet_id              UUID         NULL,          -- bound when scopes include pay:*
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at             TIMESTAMPTZ  NULL,
    revoked_by             VARCHAR(128) NULL,
    revoke_reason          TEXT         NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_delegation_grant_agent ON agent_delegation_grant (agent_id, status);

CREATE TABLE agent_delegation_token (
    jti                    VARCHAR(64)  NOT NULL,      -- JWT jti claim
    grant_id               UUID         NOT NULL REFERENCES agent_delegation_grant(id) ON DELETE CASCADE,
    agent_id               UUID         NOT NULL,
    audience               VARCHAR(512) NOT NULL,
    issued_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ  NOT NULL,
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE','REVOKED')),
    wallet_id              UUID         NULL,          -- bound when scope includes pay:*
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (jti)
);

CREATE INDEX idx_delegation_token_grant ON agent_delegation_token (grant_id, status);
