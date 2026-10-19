-- Tenant SSO/SCIM productization (core/admin/sso): per-tenant BYO-IdP
-- config + SCIM-lite bearer tokens.
--
-- tenant_sso_config: one row per tenant schema describing the customer's own
-- identity provider (Okta/Entra/Google Workspace/any OIDC or SAML 2.0 IdP)
-- that TenantSsoService mirrors into the shared Keycloak realm as an IdP
-- instance aliased tenant-<schema>. Keycloak itself is the protocol engine -
-- this table only stores what we need to (re)build that instance and show
-- status in the admin console.
--
--   client_secret_encrypted - the OIDC client secret (AES-GCM via
--     EncryptionService, same as every other stored credential). SAML has no
--     equivalent secret; the IdP's signing certificate is NOT secret (it's
--     public key material) so it is stored plaintext in saml_x509_cert.
--   The decrypted secret is deliberately never re-served: GET returns only
--     client_secret_set, never the value.
--
-- tenant_scim_token: bearer tokens for the SCIM-lite provisioning endpoint
-- (/scim/v2/Users). Stored as SHA-256 hashes, NOT encrypted - a bearer token
-- only needs comparison, and hashing means a DB read can't yield usable
-- tokens (same posture as API-key hashing conventions elsewhere).
--
-- CONTROL-PLANE tables: these describe the platform-level relationship
-- between a tenant and the shared realm - not tenant-schema business data
-- (same reasoning as control_plane.tenant itself).
--
-- Version 253: V250-259 claimed for this work in docs/AGENT-COORDINATION.md;
-- the shared controlplane+tenant version namespace applies.

CREATE TABLE IF NOT EXISTS control_plane.tenant_sso_config (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_schema           VARCHAR(64)  NOT NULL UNIQUE,
    provider_type           VARCHAR(8)   NOT NULL CHECK (provider_type IN ('OIDC','SAML')),
    alias                   VARCHAR(128) NOT NULL UNIQUE,  -- tenant-<schema>
    display_name            VARCHAR(255) NOT NULL,
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    -- OIDC fields
    oidc_discovery_url      VARCHAR(2048),
    oidc_authorization_url  VARCHAR(2048),
    oidc_token_url          VARCHAR(2048),
    oidc_userinfo_url       VARCHAR(2048),
    oidc_jwks_url           VARCHAR(2048),
    oidc_issuer             VARCHAR(2048),
    oidc_client_id          VARCHAR(512),
    client_secret_encrypted TEXT,
    -- SAML fields
    saml_entity_id          VARCHAR(2048),
    saml_sso_url            VARCHAR(2048),
    saml_x509_cert          TEXT,
    saml_name_id_format     VARCHAR(128),
    created_by              VARCHAR(255),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_synced_at          TIMESTAMPTZ  -- last successful push to Keycloak
);

CREATE TABLE IF NOT EXISTS control_plane.tenant_scim_token (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_schema VARCHAR(64)  NOT NULL,
    token_hash    VARCHAR(64)  NOT NULL UNIQUE,  -- hex SHA-256 of the bearer token
    label         VARCHAR(255) NOT NULL,
    created_by    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_tenant_scim_token_schema
    ON control_plane.tenant_scim_token (tenant_schema);
