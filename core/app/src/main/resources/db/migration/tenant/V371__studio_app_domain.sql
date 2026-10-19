-- Custom domains for published Phone-App-Studio apps (V359 studio_app).
--
-- Tenant schema: the domain row is tenant data. GLOBAL uniqueness of a
-- hostname across tenants is NOT enforceable here (each tenant has its own
-- schema), so - exactly like the slug - a VERIFIED hostname is registered in
-- control_plane.channel_account_link under
-- ExternalAccountProvider.STUDIO_APP_DOMAIN (unique on provider +
-- external_account_id). Only the first tenant to prove DNS control gets the
-- registry entry; a second tenant's verify is refused (hostname_claimed).
-- No control-plane migration is needed: provider is a free VARCHAR(32).
--
-- verification_token is a public, non-secret challenge the owner publishes
-- in a DNS TXT record at _vegaduta-verify.<hostname>.

CREATE TABLE studio_app_domain (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id              UUID NOT NULL REFERENCES studio_app(id) ON DELETE CASCADE,
    hostname            VARCHAR(128) NOT NULL,
    verification_token  VARCHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',   -- PENDING | VERIFIED | FAILED
    last_error          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at         TIMESTAMPTZ,
    last_checked_at     TIMESTAMPTZ,
    CONSTRAINT uq_studio_app_domain_hostname UNIQUE (hostname)
);

CREATE INDEX idx_studio_app_domain_app ON studio_app_domain (app_id);
