-- MCP tool-definition pinning (docs/architecture/mcp-spec-alignment.md, section "Tool pinning").
-- The MCP spec treats tool descriptions/annotations as untrusted; a remote server can change them
-- after the admin reviewed it (a "rug pull"). Each dynamic registration now records a SHA-256 over
-- the tool list the model actually sees, per-tool hashes (to name what changed) and the approved
-- baseline. Observed != pinned means "changed, re-approval required".
--
-- TENANT-schema table (no schema qualifier), like every mcp_server_registration migration. All
-- columns are nullable: NULL pinned = never pinned (the first successful tool listing after this
-- migration pins it, trust-on-first-use), so existing rows need no backfill and nothing changes
-- for them until a server's tool list later differs.
ALTER TABLE mcp_server_registration
    ADD COLUMN IF NOT EXISTS tools_pinned_hash       VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tools_pinned_manifest   TEXT,
    ADD COLUMN IF NOT EXISTS tools_pinned_at         TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS tools_pinned_by         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS tools_observed_hash     VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tools_observed_manifest TEXT,
    ADD COLUMN IF NOT EXISTS tools_changed_at        TIMESTAMP WITH TIME ZONE;
