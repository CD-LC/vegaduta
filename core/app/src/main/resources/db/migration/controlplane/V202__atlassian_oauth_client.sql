-- One-time, platform-wide OAuth client registration for Atlassian's hosted
-- remote MCP server (mcp.atlassian.com). Unlike X (BYOK - each tenant brings
-- their own Developer App, see x_credential), Atlassian's OAuth server
-- supports Dynamic Client Registration (RFC 7591, its own /v1/register
-- endpoint) - the platform registers itself with Atlassian exactly once and
-- every tenant then reuses this single client for their own per-tenant
-- Authorization Code + PKCE consent flow (see atlassian_credential, tenant
-- schema). Single row by design, same "one row, control_plane schema"
-- posture as platform_tool_credential (V174).
--
-- token_endpoint_auth_method and redirect_uri are stored (not just assumed)
-- because both are load-bearing for every future token call: Atlassian's
-- discovery document lists three possible auth methods
-- (client_secret_basic/post/none) and DCR may not grant exactly what was
-- requested, and a redirect_uri drift (e.g. a domain/environment change)
-- would otherwise fail silently with no way to tell "wrong redirect_uri"
-- apart from any other OAuth error. encrypted_client_secret is nullable -
-- a DCR server may grant a public ("none" auth method) client with no
-- secret at all, a valid outcome this table must be able to represent.
CREATE TABLE control_plane.atlassian_oauth_client (
    id                          UUID PRIMARY KEY,
    client_id                   VARCHAR(255) NOT NULL,
    encrypted_client_secret     TEXT,
    token_endpoint_auth_method  VARCHAR(32) NOT NULL,
    redirect_uri                VARCHAR(512) NOT NULL,
    registered_at               TIMESTAMPTZ NOT NULL
);
