-- V659: mcp_starter_seed - which starter MCP connectors this tenant has already been offered.
-- Tenant schema: unqualified, no tenant_id (the schema is the tenancy). Shared Flyway namespace with controlplane/.
--
-- A personal install registers a few well-known, pinned connectors (fetch, time, sequential-thinking) for its
-- owner on first start (McpStarterService). One row per starter once it has been offered, so a connector the
-- owner deletes, switches off or edits is never put back or overwritten. Empty and unused on every other shape.
CREATE TABLE IF NOT EXISTS mcp_starter_seed (
    starter_key VARCHAR(64) PRIMARY KEY,
    seeded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
