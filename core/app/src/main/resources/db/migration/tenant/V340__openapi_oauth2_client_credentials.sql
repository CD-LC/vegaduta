-- OAuth2 client-credentials auth for imported OpenAPI tool sources
-- (core/agents, com.agenticai.core.tools.openapi). Needed by the healthcare
-- clearinghouse/payer connectors: Availity (POST form to /v1/token, 5-minute
-- tokens), Optum/UHC (POST JSON to /apip/auth/v2/token) and most other
-- OAuth2-gated APIs a tenant imports.
--
-- auth_type is widened from varchar(24): the new enum name
-- OAUTH2_CLIENT_CREDENTIALS is 25 characters - one over the old bound.
--
-- oauth2_token_url   - full URL of the provider's token endpoint; validated
--                      as a public https URL at save time and re-validated
--                      (post-DNS) on every token fetch, same as base_url.
-- oauth2_client_id   - the client id / API key. Stored plaintext ON PURPOSE:
--                      it is a public identifier (like a username), the
--                      console echoes it back, and the sensitive half - the
--                      client SECRET - rides the existing encrypted_secret
--                      column, so there is still exactly one decrypt path.
-- oauth2_scope       - optional space-separated scope list (Availity requires
--                      the two scopes shown on the subscription's Client info;
--                      Optum does not use scopes).
-- oauth2_token_style - 'FORM' (application/x-www-form-urlencoded body, the
--                      RFC 6749 shape - Availity) or 'JSON' (application/json
--                      body - Optum). NULL unless auth_type is OAuth2.
--
-- TENANT-schema table (no schema qualifier, same convention as V232 itself):
-- runs once per tenant schema. Version V340: flyway.locations merges
-- controlplane/ and tenant/ into ONE shared version stream; V335-V338 were
-- reserved by the sales-automation session and V339 landed in controlplane/
-- (demo studio) while this was being written, so this took the next free
-- number (scripts/ci/flyway-lint.sh is the gate).

ALTER TABLE openapi_tool_source
    ALTER COLUMN auth_type TYPE VARCHAR(32);

ALTER TABLE openapi_tool_source
    ADD COLUMN IF NOT EXISTS oauth2_token_url   TEXT,
    ADD COLUMN IF NOT EXISTS oauth2_client_id   VARCHAR(512),
    ADD COLUMN IF NOT EXISTS oauth2_scope       VARCHAR(512),
    ADD COLUMN IF NOT EXISTS oauth2_token_style VARCHAR(8);
