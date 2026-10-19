-- Remote MCP servers from the official registry (registry.modelcontextprotocol.io)
-- integration - a registry entry can be a hosted endpoint (a "remote" with a
-- streamable-http / sse URL) rather than a locally-launched stdio subprocess.
-- Those have no command/args at all, just a URL that DynamicMcpConnectionManager
-- connects an HTTP MCP client to. The url is not secret (it's a public endpoint;
-- any credential it needs still goes through encrypted_env as a bearer token /
-- header value), so it's stored plain alongside command/args.
--
-- Nullable and additive: every existing row (static YAML servers and dynamic
-- stdio servers alike) keeps url NULL and is completely unaffected.
ALTER TABLE mcp_server_registration
    ADD COLUMN url VARCHAR(2048);
