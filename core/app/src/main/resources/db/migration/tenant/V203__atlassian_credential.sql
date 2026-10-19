-- A tenant's own Atlassian (Confluence/Jira) OAuth connection - see
-- AtlassianCredential's javadoc. Unlike x_credential, there is no per-tenant
-- client_id/client_secret here: every tenant shares the ONE platform-wide
-- OAuth client registered once via Dynamic Client Registration (see
-- control_plane.atlassian_oauth_client, V202) - this table only ever holds
-- the per-tenant OUTCOME of that tenant's own Authorization Code + PKCE
-- consent redirect.
CREATE TABLE atlassian_credential (
    id                          UUID PRIMARY KEY,
    encrypted_refresh_token     TEXT,
    status                      VARCHAR(16) NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL
);
