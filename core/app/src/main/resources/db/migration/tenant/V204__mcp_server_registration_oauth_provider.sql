-- Structural marker for a registration whose live connection headers come
-- from a real OAuth flow (e.g. "ATLASSIAN") rather than the registration's
-- own stored encrypted_env - see DynamicMcpConnectionManager#remoteHeaders.
-- Deliberately NOT keyed off McpServerRegistration.name (a per-tenant,
-- user-editable string a tenant could rename or reuse for an unrelated
-- custom server) - this column is set exactly once, by the OAuth service
-- itself, at connect time.
ALTER TABLE mcp_server_registration ADD COLUMN oauth_provider VARCHAR(32);
