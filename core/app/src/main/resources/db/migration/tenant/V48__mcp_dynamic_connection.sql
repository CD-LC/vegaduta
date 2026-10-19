-- Dynamic MCP servers (#424 / direct feedback: "how client can configure and
-- use it during runtime") - lets a tenant register a *new* MCP server
-- (command/args/credentials) from the UI and have it actually connect
-- immediately, instead of the Stage 4 MVP posture where only a server
-- someone had already hand-added to application-mcp.yml (then restarted
-- core) could ever produce real tools. See McpServerRegistration's updated
-- javadoc and DynamicMcpConnectionManager (core/agents) for how this is
-- used.
--
-- command/args are plain (never secret - "npx" and "-y @modelcontextprotocol/
-- server-brave-search" reveal nothing sensitive). encrypted_env holds the
-- server's environment variables (API keys, tokens, connection strings) as
-- one AES-256-GCM ciphertext blob (EncryptionService, same primitive every
-- other BYOK credential in this schema already uses) wrapping a small JSON
-- object - encrypting the whole map as one value rather than per-key is a
-- deliberate simplification since these rows are read as a unit (there is
-- no use case for decrypting a single env var without the rest).
ALTER TABLE mcp_server_registration
    ADD COLUMN command           VARCHAR(255),
    ADD COLUMN args               TEXT,
    ADD COLUMN encrypted_env      TEXT,
    ADD COLUMN connection_status  VARCHAR(32) NOT NULL DEFAULT 'NOT_CONFIGURED',
    ADD COLUMN connection_error   TEXT,
    ADD COLUMN connected_at       TIMESTAMPTZ;

-- Every pre-existing row (today, only ever "filesystem" in practice) is a
-- statically-YAML-wired server, not one of these new dynamic connections -
-- backfilled to STATIC so the frontend can tell the two kinds apart without
-- guessing from "command IS NULL".
UPDATE mcp_server_registration SET connection_status = 'STATIC' WHERE command IS NULL;
