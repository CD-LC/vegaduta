-- Durable per-tenant OAuth connections for remote MCP servers and for the
-- provider registry (Google/GitHub/Slack/Notion/Linear/HubSpot/Microsoft/
-- Asana/Zoom one-click Connect - McpOAuthConnectionStore / JpaMcpOAuthConnectionStore).
--
-- Why a new table instead of reusing one: atlassian_credential (V203) holds a
-- single Atlassian refresh token with no provider/endpoint/client columns, and
-- the secrets vault is a user-visible name/value list - neither can carry an
-- OAuth connection (several providers, endpoints, optional non-refreshable
-- access token). McpOAuthConnectionStore has been in-memory only since it was
-- introduced, so every restart/deploy silently disconnected every tenant; this
-- is the durable implementation it always documented as the follow-up.
--
-- Tenant-schema table (unqualified, resolved via search_path), so there is no
-- tenant_id column. All token/secret columns hold EncryptionService (AES-256-GCM)
-- ciphertext; nothing here is plaintext. resource_url is either a real MCP
-- server URL or the pseudo key oauth-provider:<id> used by the provider
-- registry.
CREATE TABLE IF NOT EXISTS mcp_oauth_connection (
    id                          UUID PRIMARY KEY,
    resource_url                VARCHAR(1024) NOT NULL,
    issuer                      VARCHAR(512)  NOT NULL,
    authorization_endpoint      TEXT          NOT NULL,
    token_endpoint              TEXT          NOT NULL,
    client_id                   VARCHAR(512)  NOT NULL,
    encrypted_client_secret     TEXT,
    token_endpoint_auth_method  VARCHAR(64),
    redirect_uri                TEXT,
    scope                       TEXT,
    encrypted_refresh_token     TEXT,
    encrypted_access_token      TEXT,
    access_token_expires_at     TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ   NOT NULL,
    updated_at                  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_mcp_oauth_connection_resource UNIQUE (resource_url)
);
